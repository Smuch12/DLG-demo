package demo.dlg;

import java.util.function.Consumer;

import demo.dlg.model.DlgWorkerRequest;
import demo.dlg.model.DlgWorkerUpdate;

public interface DlgWorker {
    void run(DlgWorkerRequest request, Consumer<DlgWorkerUpdate> updates) throws Exception;
}
