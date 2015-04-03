package software.pama;

import java.util.Date;

/**
 * Created by Pawel on 2015-04-03.
 */
public class RunInfoPiece {

    private int distance;
    private Date time;

    public RunInfoPiece() {}

    public RunInfoPiece(int distance, Date time) {
        setDistance(distance);
        setTime(time);
    }

    public void setDistance(int distance) {
        this.distance = distance;
    }

    public int getDistance() {
        return distance;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    public Date getTime() {
        return time;
    }
}
