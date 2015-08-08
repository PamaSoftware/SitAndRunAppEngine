package software.pama.communication;

import software.pama.run.RunResultPiece;

/**
 * Created by Pawel on 2015-08-08.
 */
public class OpponentPositionInfo {
    private int distance;
    private int time;

    public OpponentPositionInfo() {
    }

    public OpponentPositionInfo(int distance, int time) {
        setDistance(distance);
        setTime(time);
    }

    public OpponentPositionInfo(RunResultPiece runResultPiece) {
        setDistance(runResultPiece.getDistance());
        setTime(runResultPiece.getTime());
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
