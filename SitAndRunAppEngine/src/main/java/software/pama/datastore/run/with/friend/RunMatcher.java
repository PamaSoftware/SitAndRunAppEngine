package software.pama.datastore.run.with.friend;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import software.pama.communication.RunPreferences;

import java.io.Serializable;
import java.util.Date;

/**
 * Created by Pawel on 2015-06-18.
 */
@Entity
public class RunMatcher implements Serializable{
    @Id
    Long id;
    @Index
    String hostLogin;
    @Index
    String opponentLogin;
    RunPreferences hostRunPreferences;
    Boolean complete = false;
    Date createDate;
    Date acceptByOpponentDate;
    int distance = 0;

    public RunMatcher() {}

    public RunMatcher(String hostLogin, String opponentLogin, RunPreferences hostRunPreferences) {
        this.hostLogin = hostLogin;
        this.opponentLogin = opponentLogin;
        this.hostRunPreferences = hostRunPreferences;
        createDate = new Date();
    }

    public void setDistance(int distance) {
        this.distance = distance;
    }

    public int getDistance() {
        return distance;
    }

    public String getOpponentLogin() {
        return opponentLogin;
    }

    public void setComplete(Boolean complete) {
        this.complete = complete;
    }

    public void setAcceptByOpponentDate(Date acceptByOpponentDate) {
        this.acceptByOpponentDate = acceptByOpponentDate;
    }

    public String getHostLogin() {
        return hostLogin;
    }

    public RunPreferences getHostRunPreferences() {
        return hostRunPreferences;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public Boolean isComplete() {
        return complete;
    }

    public Date getAcceptByOpponentDate() {
        return acceptByOpponentDate;
    }
}
