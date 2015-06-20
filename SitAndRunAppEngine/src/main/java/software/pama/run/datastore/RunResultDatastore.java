package software.pama.run.datastore;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import software.pama.run.RunResult;

/**
 * Created by Pawel on 2015-06-20.
 */
@Entity
public class RunResultDatastore {
    @Id
    String id;
    RunResult runResult;

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setRunResult(RunResult runResult) {
        this.runResult = runResult;
    }

    public RunResult getRunResult() {
        return runResult;
    }
}
