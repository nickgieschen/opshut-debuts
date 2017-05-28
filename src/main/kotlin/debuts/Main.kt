package debuts

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.Protocol
import java.net.URI
import java.util.*

class App(val yahoo: Yahoo, val data: Data, val messager: Messager) {

    companion object {
        fun getPlayerId(cl: CommandLine) = cl.getOptionValue("p", null)!!

        @JvmStatic
        fun main(args: Array<String>) {

            val logger: Logger = LoggerFactory.getLogger("Main")
            val config: Config = ConfigFactory.load().getConfig("app")
            val messager = Messager(config.getString("mailApiKey"))
            val jedisPool = constructJedisPool(config.getString("redisUri"), config.getInt("redisDbIndex"))
            val data = Data(jedisPool)
            val yahoo = Yahoo(data, config)
            val app = App(yahoo, data, messager)

            try {
                val options = Options()
                options.addOption("t", "Run in $args test mode. No persistence to S3 or league.")
                options.addOption(Option.builder("p").hasArg().argName("PLAYER_ID").desc("The player id process").build())
                val cl = DefaultParser().parse(options, args)!!

                // TODO use this or from config
                //testing = cl.hasOption("t")

                val cmd = cl.argList.last()
                logger.info("executing $cmd")

                when (cmd) {
                    "addplayertostash" -> app.addPlayersToStash(getPlayerId(cl))
                    "addplayerstostash" -> app.addPlayersToStash()
                    "dropfromstash" -> app.dropPlayersFromStash()
                    "createinitialdebuts" -> app.createInitialDebuts()
                    "refreshaccesstoken" -> yahoo.getNewToken()
                    "info" -> app.getInfo(getPlayerId(cl))
                }
            } catch(e: Exception) {
                logger.error(e.message)
            }
        }
    }

    val logger: Logger by lazy { LoggerFactory.getLogger(javaClass) }

    fun getInfo(playerId: String) {

        /*
        val brPlayer = try {
            data.findDebutPlayers().first { it.id.contains(playerId) }
        } catch (e: Exception) {
            null
        }

        if (brPlayer == null) {
            log("$playerId does not exist in BR")
            return
        }
        log("$playerId is a BR debut player:\n $brPlayer")

        val yahooPlayers = yahoo.findPlayer(brPlayer)
        if (yahooPlayers.size > 1) {
            log("$playerId has multiple entries:")
            return
        } else if (yahooPlayers.isEmpty()) {
            log("$playerId does not exist in Yahoo:")
            return
        }

        val entry = Entry(null, brPlayer, yahooPlayers[0])

        log("$playerId exists in Yahoo:\n ${toJson(entry)}")

        if (data.findStashedPlayers().contains(entry)) {
            log("$playerId is in PROCESSED")
        }

        if (data.findProcessedPlayers().contains(entry)) {
            log("$playerId is in STASHED")
        }
        */
    }

    fun getUnprocessedDebuts(): List<BrPlayer> {
        val allDebuts = data.findDebutPlayers()
        val processedDebuts = data.findProcessedPlayers()
        val onStash = data.findStashedPlayers()
        return allDebuts.filterNot { (id) ->
            processedDebuts.any { (_, brPlayer) -> brPlayer.id == id }
                    || onStash.any { (_, brPlayer) -> brPlayer.id == id }
        }
    }

    fun addPlayersToStash() = addPlayersToStash(null)

    fun addPlayersToStash(playerId: String?) {

        val messageBuilder = AddedMessageBuilder("Add to Stash")

        val matchedPlayers = mutableListOf<Entry>()
        var unprocessedDebuts = getUnprocessedDebuts()

        // We're only processing the player passed in
        if (playerId != null) {
            unprocessedDebuts = unprocessedDebuts.filter { it.id.contains(playerId) }
        }

        unprocessedDebuts.forEach { player ->
            val matchesFromYahoo = yahoo.findPlayer(player)

            if (matchesFromYahoo.count() > 1) {
                messageBuilder.ambiguousNames.put(player, matchesFromYahoo)
            } else if (matchesFromYahoo.count() == 0) {
                // We consider these players processed since when Yahoo adds them they will already have debuted and hence
                // their waiver period will be appropriately after the fact that they've debuted
                data.saveProcessedPlayer(Entry(null, player, null))
                messageBuilder.unmatchedPlayers.add(player)
            } else {
                matchedPlayers.add(Entry(null, player, matchesFromYahoo[0]))
            }
        }

        // If we're here, we have a player who debuted who is also in Yahoo and who hasn't be processed or on stash
        matchedPlayers.forEach {
            val yahooPlayer = it.yahooPlayer!!
            // The player is alredy on waivers so we don't need to do anything
            if (yahooPlayer.ownership == "waivers") {
                messageBuilder.waiveredPlayers.add(it)
            } else if (yahooPlayer.ownership == "freeagents") {
                var tries = 0
                while (tries < 3) {
                    try {
                        tries++
                        yahoo.addPlayerToStash(it)
                        it.timestamp = Date()
                        data.saveStashedPlayer(it)
                        messageBuilder.stashedPlayers.add(it)
                        break
                    } catch (e: Exception) {
                        if (tries == 3) {
                            messageBuilder.errorPlayers.add(it)
                            logger.error("Couldn't add to stash ${it.toJson()}", e)
                        }
                    }
                }
            } else {
                if (yahooPlayer.OsTeam == "Stash" || yahooPlayer.OsTeam == "Stash 2") {
                    // We consider these players processed since when Yahoo adds them they will already have debuted and hence
                    // their waiver period will be appropriately after the fact that they've debuted
                    data.saveProcessedPlayer(it)
                    messageBuilder.alreadyOnStashPlayers.add(it)
                } else {
                    messageBuilder.shouldntBeOwnedPlayers.add(it)
                }
            }
        }

        messager.sendResults(messageBuilder.buildMessage())
    }


    fun dropPlayersFromStash() {

        val messageBuilder = DroppedMessageBuilder("Drop from Stash")
        val players = data.findStashedPlayers().filter { it.timestamp!!.before(aDayAgo()) }

        players.forEach {
            var tries = 0
            while (tries < 3) {
                try {
                    tries++
                    yahoo.dropPlayerFromStash(it)
                    data.deleteStashedPlayer(it)
                    data.saveProcessedPlayer(it)
                    messageBuilder.droppedFromStashPlayers.add(it)
                    break
                } catch (e: Exception) {
                    if (tries == 3) {
                        messageBuilder.errorPlayers.add(it)
                        logger.error("Couldn't drop player from stash ${it.toJson()}", e)
                    }
                }
            }
        }

        messager.sendResults(messageBuilder.buildMessage())
    }

    fun createInitialDebuts() {
        data.findDebutPlayers().forEach { player ->
            val matchesFromYahoo = yahoo.findPlayer(player)

            if (matchesFromYahoo.count() > 1) {
                // TODO log
                //abiguousNames.put(player, matchesFromYahoo)
            } else if (matchesFromYahoo.count() == 0) {
                // TODO log
                //unmatchedPlayers.add(player)
            } else {
                data.saveProcessedPlayer(Entry(null, player, matchesFromYahoo[0]))
            }
        }
    }
}

fun aDayAgo(): Date {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DATE, -1)
    return cal.time!!
}

fun constructJedisPool(redisUri: String, redisDbIndex: Int): JedisPool {
    val poolConfig = JedisPoolConfig()
    val redisURI = URI(redisUri)
    return if (redisURI.userInfo != null) {
        JedisPool(poolConfig,
                redisURI.host,
                redisURI.port,
                Protocol.DEFAULT_TIMEOUT,
                redisURI.userInfo.split(":", limit = 2)[1],
                redisDbIndex)
    } else {
        JedisPool(poolConfig, redisURI.path)
    }
}

data class BrPlayer(val id: String, val name: String, val debut: String, val team: String)

data class YahooPlayer(val name: String, val first: String, val last: String, val team: String,
                       val key: String, val id: String, val ownership: String) {
    constructor(name: String, first: String, last: String, team: String, key: String, id: String, ownership: String, osTeam: String?)
            : this(name, first, last, team, key, id, ownership) {
        this.OsTeam = osTeam
    }

    var OsTeam: String? = null
}

data class Entry(var timestamp: Date?, val brPlayer: BrPlayer, val yahooPlayer: YahooPlayer?, val stashName: String? = null) {
    override fun equals(other: Any?): Boolean {
        return (other as Entry).brPlayer.id == brPlayer.id
    }

    override fun hashCode(): Int {
        var result = timestamp?.hashCode() ?: 0
        result = 31 * result + brPlayer.hashCode()
        result = 31 * result + (yahooPlayer?.hashCode() ?: 0)
        return result
    }

    fun toJson(): String? {
        val mapper = jacksonObjectMapper()
        return mapper.writeValueAsString(this)
    }
}

