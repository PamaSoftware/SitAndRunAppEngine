package software.pama.datastore.run;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import software.pama.run.RunResult;

import java.io.Serializable;
import java.util.Date;

/**
 * Created by Pawel on 2015-06-20.
 */
@Entity
public class RunResultDatastore implements Serializable{
    @Id
    String id;
    RunResult runResult;
    Date lastDatastoreSavedTime;

    public RunResultDatastore() {
        this.lastDatastoreSavedTime = new Date();
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setLastDatastoreSavedTime(Date lastDatastoreSavedTime) {
        this.lastDatastoreSavedTime = lastDatastoreSavedTime;
    }

    public Date getLastDatastoreSavedTime() {
        return lastDatastoreSavedTime;
    }

    public void setRunResult(RunResult runResult) {
        this.runResult = runResult;
    }

    public RunResult getRunResult() {
        return runResult;
    }
}
