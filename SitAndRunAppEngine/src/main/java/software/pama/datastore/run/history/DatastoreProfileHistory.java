package software.pama.datastore.run.history;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;
import software.pama.datastore.users.DatastoreProfile;
import software.pama.run.RunResult;

import java.util.Date;

/**
 * Created by Pawel on 2015-04-03.
 */
@Entity
public class DatastoreProfileHistory {
    @Parent
    Key<DatastoreProfile> parent;
    @Id
    Long id;    //ID moze byc Long, long, String, ale tylko dla Long podczas wywolywania put i nie nadaniu wartosci ID zostanie wygenerowane automatycznie
    @Index
    float averageSpeed;
    @Index
    int totalDistance;

    Date dateOfRun;
    RunResult runResult;
    Boolean isWinner;

    public DatastoreProfileHistory() {}

    public void setParent(Key<DatastoreProfile> parent) {
        this.parent = parent;
    }

    public Key<DatastoreProfile> getParent() {
        return parent;
    }

    public Long getId() {
        return id;
    }

    public void setAverageSpeed(float averageSpeed) {
        this.averageSpeed = averageSpeed;
    }

    public float getAverageSpeed() {
        return averageSpeed;
    }

    public void setTotalDistance(int totalDistance) {
        this.totalDistance = totalDistance;
    }

    public int getTotalDistance() {
        return totalDistance;
    }

    public void setDateOfRun(Date dateOfRun) {
        this.dateOfRun = dateOfRun;
    }

    public Date getDateOfRun() {
        return dateOfRun;
    }

    public void setRunResult(RunResult runResult) {
        this.runResult = runResult;
    }

    public RunResult getRunResult() {
        return runResult;
    }

    public void setIsWinner(Boolean isWinner) {
        this.isWinner = isWinner;
    }

    public Boolean getIsWinner() {
        return isWinner;
    }
}
