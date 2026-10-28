// Manual Windows live-test helper; records one process, excluding other apps.
// Build in a Visual Studio x64 developer prompt:
// cl /EHsc /std:c++17 /DNTDDI_VERSION=0x0A00000A
// tests\windows_process_loopback.cpp /Foscratch\process_loopback.obj
// /Fescratch\process_loopback.exe /link ole32.lib mmdevapi.lib Arguments:
// audio-engine PID, output WAV, seconds, readiness-file path.
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <fstream>
#include <iostream>
#include <mmdeviceapi.h>
#include <string>
#include <vector>
#include <windows.h>
#include <wrl.h>
using namespace Microsoft::WRL;
void check(HRESULT h) {
  if (FAILED(h)) {
    std::cerr << "HRESULT " << std::hex << h << std::endl;
    throw h;
  }
}
class Completion
    : public RuntimeClass<RuntimeClassFlags<ClassicCom>,
                          IActivateAudioInterfaceCompletionHandler, FtmBase> {
public:
  HANDLE done = CreateEvent(nullptr, FALSE, FALSE, nullptr);
  ComPtr<IAudioClient> client;
  HRESULT result = E_FAIL;
  ~Completion() { CloseHandle(done); }
  STDMETHOD(ActivateCompleted)
  (IActivateAudioInterfaceAsyncOperation *op) override {
    ComPtr<IUnknown> unknown;
    HRESULT activated;
    result = op->GetActivateResult(&activated, &unknown);
    if (SUCCEEDED(result))
      result = activated;
    if (SUCCEEDED(result))
      result = unknown.As(&client);
    SetEvent(done);
    return S_OK;
  }
};
int main(int argc, char **argv) {
  try {
    if (argc != 5)
      return 2;
    check(CoInitializeEx(nullptr, COINIT_MULTITHREADED));
    AUDIOCLIENT_ACTIVATION_PARAMS params = {};
    params.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    params.ProcessLoopbackParams.TargetProcessId = std::stoul(argv[1]);
    params.ProcessLoopbackParams.ProcessLoopbackMode =
        PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;
    PROPVARIANT pv = {};
    pv.vt = VT_BLOB;
    pv.blob.cbSize = sizeof(params);
    pv.blob.pBlobData = (BYTE *)&params;
    auto completion = Make<Completion>();
    ComPtr<IActivateAudioInterfaceAsyncOperation> op;
    check(ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK,
                                      __uuidof(IAudioClient), &pv,
                                      completion.Get(), &op));
    if (WaitForSingleObject(completion->done, 10000) != WAIT_OBJECT_0)
      return 3;
    check(completion->result);
    auto client = completion->client;
    WAVEFORMATEX fmt = {};
    fmt.wFormatTag = WAVE_FORMAT_PCM;
    fmt.nChannels = 2;
    fmt.nSamplesPerSec = 48000;
    fmt.wBitsPerSample = 16;
    fmt.nBlockAlign = 4;
    fmt.nAvgBytesPerSec = 192000;
    check(client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                             AUDCLNT_STREAMFLAGS_LOOPBACK |
                                 AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                             10000000, 0, &fmt, nullptr));
    HANDLE event = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    check(client->SetEventHandle(event));
    ComPtr<IAudioCaptureClient> capture;
    check(client->GetService(IID_PPV_ARGS(&capture)));
    check(client->Start());
    std::ofstream(argv[4]).put('1');
    std::vector<char> audio;
    auto end = GetTickCount64() + (ULONGLONG)(std::stod(argv[3]) * 1000);
    while (GetTickCount64() < end) {
      WaitForSingleObject(event, 100);
      UINT32 available;
      check(capture->GetNextPacketSize(&available));
      while (available) {
        BYTE *data;
        UINT32 frames;
        DWORD flags;
        check(capture->GetBuffer(&data, &frames, &flags, nullptr, nullptr));
        auto offset = audio.size();
        audio.resize(offset + frames * 4, 0);
        if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT))
          memcpy(audio.data() + offset, data, frames * 4);
        check(capture->ReleaseBuffer(frames));
        check(capture->GetNextPacketSize(&available));
      }
    }
    check(client->Stop());
    CloseHandle(event);
    std::ofstream f(argv[2], std::ios::binary);
    auto put = [&](DWORD n) { f.write((char *)&n, 4); };
    f.write("RIFF", 4);
    put(36 + (DWORD)audio.size());
    f.write("WAVEfmt ", 8);
    put(16);
    f.write((char *)&fmt, 16);
    f.write("data", 4);
    put((DWORD)audio.size());
    f.write(audio.data(), audio.size());
    std::cout << audio.size() << " bytes" << std::endl;
    CoUninitialize();
    return 0;
  } catch (...) {
    return 1;
  }
}
