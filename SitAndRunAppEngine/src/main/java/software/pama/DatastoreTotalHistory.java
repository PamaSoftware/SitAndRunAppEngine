package software.pama;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

/**
 * Created by Pawel on 2015-04-20.
 */
@Entity
public class DatastoreTotalHistory {
    @Id
    Long id;    //ID moze byc Long, long, String, ale tylko dla Long podczas wywolywania put i nie nadaniu wartosci ID zostanie wygenerowane automatycznie
    @Index
    float averageSpeed;
    @Index
    int totalDistance;
    RunResult runResult;

    public DatastoreTotalHistory() {}

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

    public void setRunResult(RunResult runResult) {
        this.runResult = runResult;
    }

    public RunResult getRunResult() {
        return runResult;
    }
}
