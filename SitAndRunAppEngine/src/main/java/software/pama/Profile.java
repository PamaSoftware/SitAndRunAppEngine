package software.pama;

import java.io.Serializable;

/**
 * Created by Pawel on 2015-03-06.
 */
public class Profile implements Serializable {
    private String login = null;
    private int winsAmount = 0;
    private int raceAmount = 0;

    public Profile() {}

    public Profile(String login) {
        setLogin(login);
    }

    public Profile(String login, int winsAmount, int raceAmount) {
        setLogin(login);
        setWinsAmount(winsAmount);
        setRaceAmount(raceAmount);
    }

    public Profile(DatastoreProfile DP) {
        setLogin(DP.getLogin());
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
