package software.pama.users.datastore;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import software.pama.users.Profile;

/**
 * Created by Pawel on 2015-03-10.
 */
@Entity
public class DatastoreProfile {
    @Id
    private String emailID;
    @Index
    private String login = null;
    private int winsAmount = 0;
    private int raceAmount = 0;

    public DatastoreProfile() {
    }

    public DatastoreProfile(String emailID, String login) {
        setEmailID(emailID);
        setLogin(login);
    }

    public DatastoreProfile(String emailID, String login, int winsAmount, int raceAmount) {
        setEmailID(emailID);
        setLogin(login);
        setWinsAmount(winsAmount);
        setRaceAmount(raceAmount);
    }

    public DatastoreProfile(String emailID, Profile P) {
        setEmailID(emailID);
        setLogin(P.getLogin());
        setWinsAmount(P.getWinsAmount());
        setRaceAmount(P.getRaceAmount());
    }

    public void setEmailID(String emailID) {
        this.emailID = emailID;
    }

    public String getEmailID() {
        return emailID;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getLogin() {
        return login;
    }

    public void setRaceAmount(int raceAmount) {
        this.raceAmount = raceAmount;
    }

    public int getRaceAmount() {
        return raceAmount;
    }

    public void setWinsAmount(int winsAmount) {
        this.winsAmount = winsAmount;
    }

    public int getWinsAmount() {
        return winsAmount;
    }

    public void addWinRace() {
        winsAmount++;
        raceAmount++;
    }

    public void addLoseRace() {
        raceAmount++;
    }
}
