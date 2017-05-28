package debuts

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.Jsoup
import redis.clients.jedis.JedisPool

@Open
class Data(val jedisPool: JedisPool) {

    val mapper = jacksonObjectMapper()

    fun findDebutPlayers(): List<BrPlayer> {
        return Jsoup.connect("http://www.baseball-reference.com/leagues/MLB/2017-debuts.shtml").get().select("#misc_bio tbody tr").map {
            val tds = it.select("td")
            BrPlayer(tds[0].select("a").attr("href"), tds[1].text(), tds[3].attr("csk"), tds[6].text())
        }
    }

    fun findProcessedPlayers(): List<Entry> {
        jedisPool.resource.use {
            return it.smembers("processed").map { mapper.readValue<Entry>(it, Entry::class.java) }
        }
    }

    fun saveProcessedPlayer(entry: Entry) {
        jedisPool.resource.use {
            it.sadd("processed", mapper.writeValueAsString(entry))
        }
    }

    fun findStashedPlayers(): List<Entry> {
        jedisPool.resource.use {
            return it.smembers("stashed").map { mapper.readValue<Entry>(it, Entry::class.java) }
        }
    }

    fun saveStashedPlayer(entry: Entry) {
        jedisPool.resource.use {
            it.sadd("stashed", mapper.writeValueAsString(entry))
        }
    }

    fun deleteStashedPlayer(entry: Entry) {
        jedisPool.resource.use {
            it.srem("stashed", mapper.writeValueAsString(entry))
        }
    }

    fun saveAccessToken(accessToken: Yahoo.AccessToken) {
        jedisPool.resource.use {
            it.set("accessTokenSessionHandle", accessToken.sessionHandle)
            it.set("accessTokenTokenSecret", accessToken.tokenSecret)
            it.set("accessTokenToken", accessToken.token)
        }
    }

    fun fetchAccessToken(): Yahoo.AccessToken? {
        jedisPool.resource.use {
            return Yahoo.AccessToken(it.get("accessTokenSessionHandle"),
                    it.get("accessTokenToken"),
                    it.get("accessTokenTokenSecret"))
        }
    }
}
