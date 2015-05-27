package software.pama;

import java.io.Serializable;

/**
 * Created by Pawel on 2015-04-03.
 */
public class RunResultPiece implements Serializable{

    private int distance;
    private int time;

    public RunResultPiece() {
    }

    public RunResultPiece(int distance, int time) {
        setDistance(distance);
        setTime(time);
    }

    public void setDistance(int distance) {
        this.distance = distance;
    }

    public int getDistance() {
        return distance;
    }

    public void setTime(int time) {
        this.time = time;
    }

    public int getTime() {
        return time;
    }
}
