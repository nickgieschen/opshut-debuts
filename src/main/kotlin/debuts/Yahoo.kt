package debuts

import com.github.scribejava.apis.YahooApi
import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.model.OAuth1AccessToken
import com.github.scribejava.core.model.OAuthConstants
import com.github.scribejava.core.model.OAuthRequest
import com.github.scribejava.core.model.Verb
import com.typesafe.config.Config
import org.jdom2.input.SAXBuilder
import org.jdom2.output.XMLOutputter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.StringReader
import java.util.*

@Open
class Yahoo(val data: Data, val config: Config) {

    val logger: Logger by lazy { LoggerFactory.getLogger(javaClass) }

    val teamAbbrMap = mapOf(
            Pair("ARI", "Ari"),
            Pair("ATL", "Atl"),
            Pair("BAL", "Bal"),
            Pair("BOS", "Bos"),
            Pair("CHC", "ChC"),
            Pair("CHW", "CWS"),
            Pair("CIN", "Cin"),
            Pair("CLE", "Cle"),
            Pair("COL", "Col"),
            Pair("DET", "Det"),
            Pair("HOU", "Hou"),
            Pair("KCR", "KC"),
            Pair("LAA", "LAA"),
            Pair("LAD", "LAD"),
            Pair("MIA", "Mia"),
            Pair("MIL", "Mil"),
            Pair("MIN", "Min"),
            Pair("NYM", "NYM"),
            Pair("NYY", "NYY"),
            Pair("OAK", "Oak"),
            Pair("PHI", "Phi"),
            Pair("PIT", "Pit"),
            Pair("SDP", "SD"),
            Pair("SEA", "Sea"),
            Pair("SFG", "SF"),
            Pair("STL", "StL"),
            Pair("TBR", "TB"),
            Pair("TEX", "Tex"),
            Pair("TOR", "Tor"),
            Pair("WSN", "Was")
    )

    var accessToken: AccessToken? = null
        get() {
            if (field != null) {
                return field
            }

            // If not in memory try to get it from disk
            field = data.fetchAccessToken()
            if (field != null) {
                return field
            }

            // If we can't read the access token from disk we need to get a new one
            field = getNewToken()
            return field
        }


    val service = ServiceBuilder()
            .apiKey(config.getString("yahoo.clientId"))
            .apiSecret(config.getString("yahoo.clientSecret"))
            .build(YahooApi.instance())

    val leagueUrl = config.getString("yahoo.leagueUrl")
    val stash2Key = config.getString("yahoo.stash2Key")

    fun findPlayer(player: BrPlayer): List<YahooPlayer> {
        val lastPartOfName = player.name.substringAfterLast(" ")
        val response = sendRequest(Verb.GET, "$leagueUrl/players;search=$lastPartOfName/ownership")
        val sax = SAXBuilder()
        val doc = sax.build(StringReader(response))
        val ns = doc.rootElement.namespace
        val p = doc.rootElement.getChild("league", ns).getChild("players", ns).children.map {
            print(XMLOutputter().outputString(it))
            YahooPlayer(it.getChild("name", ns).getChildText("full", ns),
                    it.getChild("name", ns).getChildText("ascii_first", ns),
                    it.getChild("name", ns).getChildText("ascii_last", ns),
                    it.getChildText("editorial_team_abbr", ns),
                    it.getChildText("player_key", ns),
                    it.getChildText("player_id", ns),
                    it.getChild("ownership", ns).getChildText("ownership_type", ns),
                    it.getChild("ownership", ns).getChildText("owner_team_name", ns))
        }
        return p.filter {
            it.last.endsWith(lastPartOfName) && it.team == teamAbbrMap[player.team]
        }
    }

    fun dropPlayerFromStash(player: Entry) {
            val dropPayload = """<fantasy_content>
      <transaction>
        <type>drop</type>
        <player>
          <player_key>${player.yahooPlayer!!.key}</player_key>
          <transaction_data>
            <type>drop</type>
            <source_team_key>$stash2Key</source_team_key>
          </transaction_data>
        </player>
      </transaction>
    </fantasy_content>"""
            sendRequest(Verb.POST, "$leagueUrl/transactions?format=xml", dropPayload)
    }

    fun addPlayerToStash(player: Entry) {
            val addPayload = """<fantasy_content>
      <transaction>
        <type>add</type>
        <player>
          <player_key>${player.yahooPlayer!!.key}</player_key>
          <transaction_data>
            <type>add</type>
            <destination_team_key>$stash2Key</destination_team_key>
          </transaction_data>
        </player>
      </transaction>
    </fantasy_content>"""
            sendRequest(Verb.POST, "$leagueUrl/transactions?format=xml", addPayload)
    }

    fun sendRequest(verb: Verb, url: String, payload: String? = null): String? {
        val sendRequestFn = fun(handler401: () -> String?): String? {
            val request = OAuthRequest(verb, url, service)
            if (payload != null) {
                request.addPayload(payload)
                request.addHeader("Content-type", "application/xml")
            }
            service.signRequest(accessToken, request)
            val response = request.send()
            if (response.code == 401) {
                return handler401()
            } else {
                if (response.isSuccessful) {
                    return response.body
                } else {
                    println(response.message)
                    println(response.body)
                    throw Exception()
                }
            }
        }

        val refresh = fun(): String? {
            println("We need to refresh the access token or get a new one")
            accessToken = null
            refreshToken()
            if (accessToken == null) {
                getNewToken()
            }
            return sendRequestFn {
                println("Could neither refresh nor get new token")
                throw Exception()
            }
        }

        return sendRequestFn(refresh)
    }

    fun refreshToken() {
        accessToken!!.let {
            val request = OAuthRequest(service.api.accessTokenVerb, "https://api.login.yahoo.com/oauth/v2/get_token", service)
            request.addOAuthParameter(OAuthConstants.TOKEN, it.token)
            request.addOAuthParameter("oauth_session_handle", it.sessionHandle)

            request.addOAuthParameter(OAuthConstants.TIMESTAMP, service.api.timestampService.timestampInSeconds)
            request.addOAuthParameter(OAuthConstants.NONCE, service.api.timestampService.nonce)
            request.addOAuthParameter(OAuthConstants.CONSUMER_KEY, config.getString("yahoo.clientId"))
            request.addOAuthParameter(OAuthConstants.SIGN_METHOD, service.api.signatureService.signatureMethod)
            val sig = service.api.signatureService.getSignature(service.api.baseStringExtractor.extract(request), config.getString("yahoo.clientSecret"), it.tokenSecret)
            request.addOAuthParameter(OAuthConstants.SIGNATURE, sig)

            request.addHeader(OAuthConstants.HEADER, service.api.headerExtractor.extract(request))
            val response = request.send()
            if (response.isSuccessful) {
                AccessToken(service.api.accessTokenExtractor.extract(response.body)).let {
                    data.saveAccessToken(it)
                    accessToken = it
                }
            } else {
                logger.error("Couldn't refresh access token\n${response.message}\n${response.body}")
            }
        }
    }

    fun getNewToken(): AccessToken {
        val requestToken = service.requestToken
        println("Paste the response from this link to create a new access token:")
        println(service.getAuthorizationUrl(requestToken))
        val oAuthVerifier = Scanner(System.`in`).nextLine()
        println("Thanks! Getting auth token")
        val accessToken = AccessToken(service.getAccessToken(requestToken, oAuthVerifier))
        data.saveAccessToken(accessToken)
        return accessToken
    }

    class AccessToken : OAuth1AccessToken {
        val sessionHandle: String

        constructor(accessToken: OAuth1AccessToken) : super(accessToken.token, accessToken.tokenSecret) {
            sessionHandle = accessToken.rawResponse.split("&").associate { it ->
                val parts = it.split("=")
                Pair(parts[0], parts[1])
            }["oauth_session_handle"] ?: throw Exception("Session handle could not be extracted.")
        }

        constructor(sessionHandle: String, token: String, tokenSecret: String) : super(token, tokenSecret){
            this.sessionHandle = sessionHandle
        }
    }
}
