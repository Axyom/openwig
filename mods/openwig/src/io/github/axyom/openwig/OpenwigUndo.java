package io.github.axyom.openwig;

import bitwig.obf.PiG; // a timeline (arranger or launcher clip content)
import com.bitwig.flt.document.api.document.ApiObject;
import com.bitwig.ramona.core.dEy; // an undo-tracked document object
import com.bitwig.ramona.core.jyE; // a document model object that can open undo transactions

/** Runs an edit as one native undo step on the object the API wrapper points at. */
public final class OpenwigUndo {
    private OpenwigUndo() {}

    public static void run(Object api, Runnable action) {
        jyE model = (jyE) ((ApiObject) api).getTarget();
        dEy[] tracked = model instanceof PiG timeline
                ? new dEy[]{timeline.aKN()} // the timeline's undo-tracked content
                : new dEy[0];
        if (!model.beI("Insert Clip", tracked)) { // begin a named transaction
            throw new IllegalStateException("Bitwig refused clip undo transaction");
        }
        try {
            action.run();
        } finally {
            model.xgz(); // end the transaction
        }
    }
}
