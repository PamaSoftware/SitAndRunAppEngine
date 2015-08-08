package software.pama.communication;

import java.io.Serializable;

/**
 * Created by Pawel on 2015-06-21.
 */
public class RunStartInfo implements Serializable {

    private int distance;
    private int time;

    public RunStartInfo() {
    }

    public RunStartInfo(int distance, int time) {
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