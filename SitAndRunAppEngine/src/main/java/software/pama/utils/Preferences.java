package software.pama.utils;

import java.io.Serializable;

/**
 * Created by Pawel on 2015-04-03.
 */
public class Preferences implements Serializable{
    private int aspiration;
    private int reservation;

    public Preferences() {}

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
