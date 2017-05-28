package debuts

import com.sun.jersey.api.client.Client
import com.sun.jersey.api.client.ClientResponse
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter
import com.sun.jersey.core.util.MultivaluedMapImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.*
import javax.ws.rs.core.MediaType

class Messager(val mailApiKey: String){

    val logger: Logger by lazy { LoggerFactory.getLogger(javaClass) }

    fun sendResults(message: Message) {
        try {
            val client = Client.create()
            client.addFilter(HTTPBasicAuthFilter("api", mailApiKey))
            val webResource = client.resource("https://api.mailgun.net/v3/sandbox3399bf9e4d004e239afcc5e0e0b1c336.mailgun.org/messages")
            val formData = MultivaluedMapImpl()
            formData.add("from", "Mailgun Sandbox <postmaster@sandbox3399bf9e4d004e239afcc5e0e0b1c336.mailgun.org>")
            formData.add("to", "nick@nickgieschen.com")
            formData.add("subject", "Debuts: ${message.subject} - ${SimpleDateFormat("yyyy-MM-dd").format(Calendar.getInstance().time)}")
            formData.add("text", message.body)
            webResource.type(MediaType.APPLICATION_FORM_URLENCODED).post(ClientResponse::class.java, formData)
        } catch (e: Exception) {
            logger.error("Couldn't send results", null, e)
        }
    }
}

class Message(val subject: String, val body: String)

abstract class MessageBuilder(val subject: String) {
    val errorPlayers = mutableListOf<Entry>()
    abstract fun buildBody() : String
    fun buildMessage() : Message {
        return Message(subject, buildBody())
    }
}

class DroppedMessageBuilder(subject: String) : MessageBuilder(subject) {

    val droppedFromStashPlayers = mutableListOf<Entry>()

    override fun buildBody(): String {
        return """The following players were dropped from stash:
        ${droppedFromStashPlayers.fold("") { acc, entry -> acc + entry.toJson() + "\n" }}

        There were errors processing the following players:
        ${errorPlayers.fold("") { acc, entry -> acc + entry.toJson() + "\n" }}"""
    }
}

class AddedMessageBuilder(subject: String) : MessageBuilder(subject) {

    val ambiguousNames = mutableMapOf<BrPlayer, List<YahooPlayer>>()
    val unmatchedPlayers = mutableListOf<BrPlayer>()
    val stashedPlayers = mutableListOf<Entry>()
    val waiveredPlayers = mutableListOf<Entry>()
    val alreadyOnStashPlayers = mutableListOf<Entry>()
    val shouldntBeOwnedPlayers = mutableListOf<Entry>()

    override fun buildBody(): String {
        return """The following players were added to stash:
        ${stashedPlayers.fold("") { acc, entry -> acc + entry.toJson() + "\n" }}

        The following players had no matches and were added to processed:
        ${unmatchedPlayers.fold("") { acc, entry -> acc + Entry(null, entry, null).toJson() + "\n" }}

        The following players were waivered and were added to processed:
        ${waiveredPlayers.fold("") { acc, entry -> acc + entry.toJson() + "\n" }}

        There following players were on Stash already and were added to processed:
        ${alreadyOnStashPlayers.fold("") { acc, entry -> acc + entry.toJson() + "\n" }}

        The following players had multiple matches:

        The following players were on another team, but had not gone through waivers:
        ${errorPlayers.fold("") { acc, entry -> acc + entry.toJson() + "\n" }}

        There were errors processing the following players:
        ${errorPlayers.fold("") { acc, entry -> acc + entry.toJson() + "\n" }}"""
    }
}

