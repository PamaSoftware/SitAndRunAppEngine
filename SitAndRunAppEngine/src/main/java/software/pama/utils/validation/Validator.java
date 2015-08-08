package software.pama.utils.validation;

import software.pama.run.RunResult;
import software.pama.run.RunResultPiece;
import software.pama.communication.RunPreferences;

import java.util.Iterator;

/**
 * Created by Pawel on 2015-06-18.
 */
public class Validator {
    public static boolean isPreferencesCorrect(RunPreferences runPreferences) {
        int difference = runPreferences.getAspiration() - runPreferences.getReservation();
        if(difference < 0)
            difference = -difference;
        return (runPreferences.getReservation() >= 500 && runPreferences.getAspiration() >= 500 && difference >= 100);
    }

    public static boolean isLoginCorrect(String login) {
        return login.matches("[a-z0-9]+");
    }

    public static boolean isRunResultCorrect(RunResult runResult) {
        RunResultPiece lastPiece = null;
        Iterator<RunResultPiece> iterator = runResult.getResults().iterator();
        while(iterator.hasNext()) {
            if(lastPiece == null) {
                lastPiece = iterator.next();
                if(lastPiece.getDistance() < 0 || lastPiece.getTime() < 0)
                    return false;
                continue;
            }
            RunResultPiece currentPiece = iterator.next();
            if(lastPiece.getTime() >= currentPiece.getTime() || lastPiece.getDistance() > currentPiece.getDistance())
                return false;
            lastPiece = currentPiece;
        }
        return true;
    }

    public static boolean isForecastCorrect(int forecast) {
        if(forecast < 0 || forecast > 30)
            return false;
        return true;
    }
}
