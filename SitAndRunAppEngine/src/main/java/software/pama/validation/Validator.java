package software.pama.validation;

import software.pama.run.RunResult;
import software.pama.run.RunResultPiece;
import software.pama.utils.Preferences;

import java.util.Iterator;

/**
 * Created by Pawel on 2015-06-18.
 */
public class Validator {
    public static boolean isPreferencesCorrect(Preferences preferences) {
        int difference = preferences.getAspiration() - preferences.getReservation();
        if(difference < 0)
            difference = -difference;
        return (preferences.getReservation() >= 500 && preferences.getAspiration() >= 500 && difference >= 100);
    }

    public static boolean isLoginCorrect(String login) {
        /*
        Opis dzialania:
        Sprawdzenie czy login sklada sie tylko i wylacznie z: a-z lub 0-9
        Tak:
            Zwracamy true
        Nie:
            Zwracamy false
         */
        return login.matches("[a-z0-9]+");
    }

    public static boolean isRunResultCorrect(RunResult runResult) {
        RunResultPiece lastPiece = null;
        Iterator<RunResultPiece> iterator = runResult.getResults().iterator();
        while(iterator.hasNext()) {
            if(lastPiece == null) {
                lastPiece = iterator.next();
                continue;
            }
            RunResultPiece currentPiece = iterator.next();
            if(lastPiece.getTime() >= currentPiece.getTime() || lastPiece.getDistance() > currentPiece.getDistance())
                return false;
            lastPiece = currentPiece;
        }
        return true;
    }
}
