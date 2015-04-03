package software.pama;

/**
 * Created by Pawel on 2015-03-07.
 */
public class WrappedBoolean {

    private final Boolean result;

    public WrappedBoolean(Boolean result) {
        this.result = result;
    }

    public Boolean getResult() {
        return result;
    }
}