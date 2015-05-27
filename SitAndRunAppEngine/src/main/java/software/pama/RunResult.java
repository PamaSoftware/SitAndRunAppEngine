package software.pama;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Pawel on 2015-04-03.
 */
public class RunResult implements Serializable{
    List<RunResultPiece> results = new ArrayList<>();

    public RunResult() {}

    public RunResult(List<RunResultPiece> runResultPieceList) {
        results.addAll(runResultPieceList);
    }

    public void addResult(RunResultPiece runResultPiece) {
        results.add(runResultPiece);
    }

    public void addResults(List<RunResultPiece> runResultPieceList) {
        results.addAll(runResultPieceList);
    }

    public List<RunResultPiece> getResults() {
        return results;
    }

    public void clearResults() {
        results.clear();
    }
}
