package debuts

import com.nhaarman.mockito_kotlin.*
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.context
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import java.util.*

object Tests : Spek({

    context("adding to stash") {

        val debutedPlayer = BrPlayer("1", "Babe Ruth", "", "foo")
        var mockData = mock<Data>()
        var mockYahoo = mock<Yahoo>()
        val config: Config = ConfigFactory.load()
        var app = App(config, mockYahoo, mockData, messager)

        beforeEachTest {
            mockData = mock<Data>()
            mockYahoo = mock<Yahoo>()
            whenever(mockData.findDebutPlayers()).thenReturn(listOf(debutedPlayer))
            app = App(config, mockYahoo, mockData, messager)
        }

        on("adding player to stash") {
            val yahooPlayer = YahooPlayer("a", "b", "c", "d", "e", "f", "freeagents")
            whenever(mockData.findProcessedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockData.findStashedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockYahoo.findPlayer(debutedPlayer)).thenReturn(listOf(yahooPlayer))

            app.addPlayersToStash()

            it("should add player to stash") {
                verify(mockYahoo).addPlayerToStash(any())
                verify(mockData).saveStashedPlayer(any())
            }
        }

        on("adding player to stash which has been processed") {
            val yahooPlayer = YahooPlayer("a", "b", "c", "d", "e", "f", "freeagents")
            whenever(mockData.findProcessedPlayers()).thenReturn(listOf(Entry(null, debutedPlayer, null)))
            whenever(mockData.findStashedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockYahoo.findPlayer(debutedPlayer)).thenReturn(listOf(yahooPlayer))

            app.addPlayersToStash()

            it("should not add player to stash") {
                verify(mockYahoo, never()).addPlayerToStash(any())
                verify(mockData, never()).saveStashedPlayer(any())
            }
        }

        on("adding player to stash which is on stash") {
            val yahooPlayer = YahooPlayer("a", "b", "c", "d", "e", "f", "freeagents")
            whenever(mockData.findProcessedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockData.findStashedPlayers()).thenReturn(listOf(Entry(null, debutedPlayer, null)))
            whenever(mockYahoo.findPlayer(debutedPlayer)).thenReturn(listOf(yahooPlayer))

            app.addPlayersToStash()

            it("should not add player to stash") {
                verify(mockYahoo, never()).addPlayerToStash(any())
                verify(mockData, never()).saveStashedPlayer(any())
            }
        }

        // TODO should verify it gets added to log
        on("adding player to stash of which Yahoo has multiple matches") {
            val yahooPlayer = YahooPlayer("a", "b", "c", "d", "e", "f", "freeagents")
            whenever(mockData.findProcessedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockData.findStashedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockYahoo.findPlayer(debutedPlayer)).thenReturn(listOf(yahooPlayer, yahooPlayer))

            app.addPlayersToStash()

            it("should not add player to stash") {
                verify(mockYahoo, never()).addPlayerToStash(any())
                verify(mockData, never()).saveStashedPlayer(any())
            }
        }

        on("adding player to stash which cannot be found in Yahoo") {
            whenever(mockData.findProcessedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockData.findStashedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockYahoo.findPlayer(debutedPlayer)).thenReturn(listOf<YahooPlayer>())

            app.addPlayersToStash()

            it("should be added to processed") {
                verify(mockData).saveProcessedPlayer(any())
            }
        }

        // TODO confirm add to log
        on("adding player to stash which is already on waivers in Yahoo"){
            val yahooPlayer = YahooPlayer("a", "b", "c", "d", "e", "f", "waivers")
            whenever(mockData.findProcessedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockData.findStashedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockYahoo.findPlayer(debutedPlayer)).thenReturn(listOf(yahooPlayer))

            app.addPlayersToStash()

            it("should not be added to stash") {
                verify(mockYahoo, never()).addPlayerToStash(any())
            }
        }

        // TODO verify message
        on("adding player to stash which is already on stash"){
            val yahooPlayer = YahooPlayer("a", "b", "c", "d", "e", "f", "x", "Stash")
            whenever(mockData.findProcessedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockData.findStashedPlayers()).thenReturn(listOf<Entry>())
            whenever(mockYahoo.findPlayer(debutedPlayer)).thenReturn(listOf(yahooPlayer))

            app.addPlayersToStash()

            it("should not be added to stash") {
                verify(mockYahoo, never()).addPlayerToStash(any())
                verify(mockData).saveProcessedPlayer(any())
            }
        }
    }

    context("dropping from stash") {

        var mockData = mock<Data>()
        var mockYahoo = mock<Yahoo>()
        val config: Config = ConfigFactory.load()
        var app = App(config, mockYahoo, mockData, messager)

        beforeEachTest {
            mockData = mock<Data>()
            mockYahoo = mock<Yahoo>()
            app = App(config, mockYahoo, mockData, messager)
        }

        on("dropping player from stash") {

            val cal = Calendar.getInstance()
            cal.add(Calendar.DATE, -2)
            val twoDaysAgo = cal.time!!

            val entry = Entry(
                twoDaysAgo,
                BrPlayer("a", "b", "c", "d"),
                YahooPlayer("a", "b", "c", "d", "e", "f", "x"),
                "stashName")

            whenever(mockData.findStashedPlayers()).thenReturn(listOf(entry))

            app.dropPlayersFromStash()

            it("should add player to stash") {
                verify(mockYahoo).dropPlayerFromStash(any())
                verify(mockData).deleteStashedPlayer(any())
                verify(mockData).saveProcessedPlayer(any())
            }
        }
    }

})
