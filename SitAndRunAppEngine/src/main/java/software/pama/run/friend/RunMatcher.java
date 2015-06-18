package software.pama.run.friend;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import software.pama.utils.Preferences;

import java.util.Date;

/**
 * Created by Pawel on 2015-06-18.
 */
@Entity
public class RunMatcher {
    @Id
    Long id;
    @Index
    String hostLogin;
    @Index
    String opponentLogin;
    Preferences hostPreferences;
    Preferences opponentPreferences;
    Boolean complete = false;
    Date createDate;
    Date acceptByOpponentDate;

    public RunMatcher(String hostLogin, String opponentLogin, Preferences hostPreferences) {
        this.hostLogin = hostLogin;
        this.opponentLogin = opponentLogin;
        this.hostPreferences = hostPreferences;
        createDate = new Date();
    }

    public void setOpponentPreferences(Preferences opponentPreferences) {
        this.opponentPreferences = opponentPreferences;
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

    public Preferences getHostPreferences() {
        return hostPreferences;
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
