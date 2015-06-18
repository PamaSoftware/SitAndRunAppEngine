package software.pama.run.datastore;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import software.pama.run.RunResult;
import software.pama.utils.Preferences;

import java.io.Serializable;
import java.util.Date;

/**
 * This class represent current state of current run.
 * It is stored in Datastore and also in Memcache under key which is login of the host of the run.
 */
@Entity
public class CurrentRunInformation implements Serializable{
    @Id
    Long id;
    int distance = 0;
    @Index
    String ownerLogin;
    @Index
    String hostLogin;
    Preferences hostPreferences;
    RunResult hostRunResult;
    RunResult opponentRunResult;
    boolean isRunWithRandom;
    boolean isDuringRace = false;
    Date lastDatastoreSavedTime;

    public void setOwnerLogin(String ownerLogin) {
        this.ownerLogin = ownerLogin;
    }

    public String getOwnerLogin() {
        return ownerLogin;
    }

    public void setDistance(int distance) {
        this.distance = distance;
    }

    public int getDistance() {
        return distance;
    }

    public void setHostLogin(String hostLogin) {
        this.hostLogin = hostLogin;
    }

    public String getHostLogin() {
        return hostLogin;
    }

    public void setHostPreferences(Preferences hostPreferences) {
        this.hostPreferences = hostPreferences;
    }

    public Preferences getHostPreferences() {
        return hostPreferences;
    }

    public void setHostRunResult(RunResult hostRunResult) {
        this.hostRunResult = hostRunResult;
    }

    public RunResult getHostRunResult() {
        return hostRunResult;
    }

    public void setOpponentRunResult(RunResult opponentRunResult) {
        this.opponentRunResult = opponentRunResult;
    }

    public RunResult getOpponentRunResult() {
        return opponentRunResult;
    }

    public void setRunWithRandom(boolean isRunWithRandom) {
        this.isRunWithRandom = isRunWithRandom;
    }

    public void setLastDatastoreSavedTime(Date lastDatastoreSavedTime) {
        this.lastDatastoreSavedTime = lastDatastoreSavedTime;
    }

    public Date getLastDatastoreSavedTime() {
        return lastDatastoreSavedTime;
    }

    public void setDuringRace(boolean isDuringRace) {
        this.isDuringRace = isDuringRace;
    }

    public boolean isRunWithRandom() {return isRunWithRandom;}
}
