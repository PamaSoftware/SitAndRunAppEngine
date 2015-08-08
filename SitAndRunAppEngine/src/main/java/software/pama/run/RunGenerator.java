package software.pama.run;

import software.pama.communication.RunPreferences;
import software.pama.datastore.run.history.DatastoreTotalHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Created by Pawel on 2015-08-08.
 */
public class RunGenerator {
    public static DatastoreTotalHistory runGenerator(RunPreferences runPreferences, float avgSpeed) {
        /*
        Dobieramy dystans tak by byl losowy pomiedzy aspiracja a srodkiem miedzy aspiracja a rezerwacja
         */
        int lowLimit, highLimit, distance;
        boolean isHighAspiration;
        if(runPreferences.getAspiration() <= runPreferences.getReservation()) {
            lowLimit = runPreferences.getAspiration();
            highLimit = runPreferences.getReservation();
            isHighAspiration = false;
        } else {
            lowLimit = runPreferences.getReservation();
            highLimit = runPreferences.getAspiration();
            isHighAspiration = true;
        }

        int range = (highLimit - lowLimit)/2;
        Random generator = new Random();
        int i = generator.nextInt(range);
        if(isHighAspiration)
            distance = highLimit - i;
        else
            distance = lowLimit + i;

        DatastoreTotalHistory opponentHistory = new DatastoreTotalHistory();
        opponentHistory.setTotalDistance(distance);
        opponentHistory.setAverageSpeed(avgSpeed);

        int lastTime = (int) (distance/(avgSpeed*1000/3600));
        RunResultPiece firstPiece = new RunResultPiece(0,0);
        RunResultPiece lastPiece = new RunResultPiece(distance, lastTime);


        RunResult runResult = new RunResult();
        runResult.addResult(firstPiece);

        //trzeba wygenerowac losowo brakujace elementy pomiedzy firstPiece a lastPiece
        //generujemy liste z przebiegiem sekund od 5 do mniej niz lastTime skaczac losowo co 10 - 12 sekund

        List<Integer> timeList = new ArrayList<>();
        int time = 10;
        while (time < lastPiece.getTime()) {
            timeList.add(time);
            //dodajemy miedzy 10 a 12 sekund
            time += generator.nextInt(2) + 10;
        }

        //sprawdzamy rozmiar wygenerowanej listy + pierwszy wpis i ostatni i dzielimy caly dystans przez ta wartosc
        int distancePiece = distance/(2 + timeList.size());

        int distanceSum = 0;

        for (Integer aTimeList : timeList) {
            distanceSum += distancePiece;
            RunResultPiece runResultPiece = new RunResultPiece(distanceSum, aTimeList);
            runResult.addResult(runResultPiece);
        }

        runResult.addResult(lastPiece);
        opponentHistory.setRunResult(runResult);
        return opponentHistory;
    }
}
