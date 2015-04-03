package software.pama;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Pawel on 2015-04-03.
 */
public class RunResults {
    List<RunInfoPiece> results = new ArrayList<>();

    public RunResults() {}

    public RunResults(List<RunInfoPiece> runInfoPieceList) {
        results.addAll(runInfoPieceList);
    }

    public void addResult(RunInfoPiece runInfoPiece) {
        results.add(runInfoPiece);
    }

    public void addResults(List<RunInfoPiece> runInfoPieceList) {
        results.addAll(runInfoPieceList);
    }

    public List<RunInfoPiece> getResults() {
        return results;
    }

    public void clearResults() {
        results.clear();
    }
}
