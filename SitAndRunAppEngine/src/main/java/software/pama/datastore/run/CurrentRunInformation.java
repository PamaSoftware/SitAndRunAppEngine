package software.pama.datastore.run;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import software.pama.run.RunResult;
import software.pama.communication.RunPreferences;

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
    String hostLogin;
    @Index
    String opponentLogin;
    //used in run with friend only
    String hostRunResultId;
    String opponentRunResultId;
    //used in run with random only
    RunResult hostRunResult;
    RunResult opponentRunResult;
    boolean isRunWithRandom;
    Date createDate;
    Date lastDatastoreSavedTime;
    //used in run with random only
    RunPreferences hostRunPreferences;
    //if this is true and you will cancel this run system will also change statistics
    boolean started = false;
    boolean winnerExist = false;

    public CurrentRunInformation() {
        this.createDate = new Date();
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setWinnerExist(boolean winnerExist) {
        this.winnerExist = winnerExist;
    }

    public boolean isWinnerExist() {
        return winnerExist;
    }

    public void setHostRunResultId() {
        this.hostRunResultId = lastDatastoreSavedTime.toString().concat(hostLogin);
    }

    public String getHostRunResultId() {
        return hostRunResultId;
    }

    public void setOpponentRunResultId() {
        this.opponentRunResultId = lastDatastoreSavedTime.toString().concat(opponentLogin);
    }

    public String getOpponentRunResultId() {
        return opponentRunResultId;
    }

    public void setStarted(boolean started) {
        this.started = started;
    }

    public boolean getStarted() {
        return started;
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

    public void setOpponentLogin(String opponentLogin) {
        this.opponentLogin = opponentLogin;
    }

    public String getOpponentLogin() {
        return opponentLogin;
    }

    public void setHostRunPreferences(RunPreferences hostRunPreferences) {
        this.hostRunPreferences = hostRunPreferences;
    }

    public RunPreferences getHostRunPreferences() {
        return hostRunPreferences;
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

    public boolean isRunWithRandom() {return isRunWithRandom;}
}
