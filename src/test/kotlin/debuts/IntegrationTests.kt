package debuts

import com.typesafe.config.ConfigFactory
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.context
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import java.util.*

object IntegrationTests : Spek({

    val jedisPool = constructJedisPool("localhost", 1)
    val config = ConfigFactory.load().getConfig("app")
    val data = Data(jedisPool)
    val yahoo = Yahoo(data, config)

    val brPlayer = BrPlayer("1", "Luke Maile", "3", "TOR")
    val entry = Entry(Calendar.getInstance().time!!,
            brPlayer,
            YahooPlayer("1", "2", "3", "4", "9479", "9479", "7", "8"))

    beforeEachTest {
        //jedisPool.resource.use(Jedis::flushDB)
    }

    context("data") {
        on("saving processed player") {
            it("saves processed player") {
                data.saveProcessedPlayer(entry)
                val savedEntry = data.findProcessedPlayers().first()
                assertThat(savedEntry).isEqualToComparingFieldByField(entry)
            }
        }

        on("saving stashed player") {
            it("saves stashed player") {
                data.saveStashedPlayer(entry)
                val savedEntry = data.findStashedPlayers().first()
                assertThat(savedEntry).isEqualToComparingFieldByField(entry)
            }
        }

        on("saving deleting stashed player") {
            it("deletes stashed player") {
                data.saveStashedPlayer(entry)
                val numItems = jedisPool.resource.use {
                    it.scard("stashed")
                }
                assertThat(numItems).isEqualTo(1)
                data.deleteStashedPlayer(entry)
                val numItems2 = jedisPool.resource.use {
                    it.scard("stashed")
                }
                assertThat(numItems2).isEqualTo(0)
            }
        }

        on("finding debut players"){
            it("finds debut players") {
                val players = data.findDebutPlayers()
                assertThat(players).isNotNull
            }
        }
    }

    context("yahoo") {

        on("finding a player") {
            it("finds a player") {
                val player = yahoo.findPlayer(brPlayer)
                assertThat(player).isNotNull
            }
        }

        on("adding a player to stash") {
            it("adds a player to stash") {
                val yahooPlayers = yahoo.findPlayer(brPlayer)
                val yahooPlayer = yahooPlayers.first()
                yahoo.addPlayerToStash(Entry(null, brPlayer, yahooPlayer))
            }
        }

        on("dropping a player from stash") {
            it("drops a player from stash") {
                val yahooPlayers = yahoo.findPlayer(brPlayer)
                val yahooPlayer = yahooPlayers.first()
                yahoo.dropPlayerFromStash(Entry(null, brPlayer, yahooPlayer))
            }
        }
    }
})

