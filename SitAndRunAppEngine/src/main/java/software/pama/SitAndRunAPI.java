package software.pama;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.Named;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.users.User;

import java.io.IOException;

/**
 * Created by Pawel on 2015-01-17.
 */
@Api(
        name = "sitAndRunApi",
        version = "v1",
        scopes = {Constants.EMAIL_SCOPE},
        clientIds = {Constants.WEB_CLIENT_ID, Constants.ANDROID_CLIENT_ID, Constants.API_EXPLORER_CLIENT_ID},
        audiences = {Constants.ANDROID_AUDIENCE}
)
public class SitAndRunAPI {

    @ApiMethod(name = "getSupportedVersion", path = "getSupportedVersion")
    public int getSupportedVersion() {
        return Constants.ANDROID_APP_SUPPORTED_VERSION;
    }

    @ApiMethod(name = "signIn", path = "signIn")
    public boolean signIn(User user) throws OAuthRequestException {
        if (user == null) {
            throw new OAuthRequestException("User was not specified.");
        }
        //TODO sprawdzenie czy user.getEmail() jest w bazie, jesli tak to zwracamy true i wyciagamy dane do memcache
        return true;
    }

    @ApiMethod(name = "signUp", path = "signUp")
    public boolean signUp(@Named("login")String login, User user) throws OAuthRequestException {
        if (user == null) {
            throw new OAuthRequestException("User was not specified.");
        }
        //TODO sprawdzenie czy user.getEmail() jest w bazie: tak - zwracamy false, nie - sprawdzamy czy login zajety: tak - false, nie - zwracamy true zakladamy wpisy w bazie oraz wyciagamy dane do memcache jako ze zalogowany
        return true;
    }

    @ApiMethod(name = "removeAccount", path = "removeAccount")
    public boolean removeAccount(@Named("login")String login, User user) throws OAuthRequestException {
        if (user == null) {
            throw new OAuthRequestException("User was not specified.");
        }
        //TODO sprawdzenie czy user.getEmail() jest w bazie: nie - zwracamy false, tak - sprawdzamy czy login przynalezy do maila: nie - false, tak - zwracamy true usuwamy uzytkownika
        return true;
    }
}
