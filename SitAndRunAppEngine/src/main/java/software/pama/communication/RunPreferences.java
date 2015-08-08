package software.pama.communication;

import java.io.Serializable;

/**
 * Created by Pawel on 2015-04-03.
 */
public class RunPreferences implements Serializable{
    private int aspiration;
    private int reservation;

    public RunPreferences() {}

    public RunPreferences(int aspiration, int reservation) {
        this.aspiration = aspiration;
        this.reservation = reservation;
    }

    public void setAspiration(int aspiration) {
        this.aspiration = aspiration;
    }

    public int getAspiration() {
        return aspiration;
    }

    public void setReservation(int reservation) {
        this.reservation = reservation;
    }

    public int getReservation() {
        return reservation;
    }
}
