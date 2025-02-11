package com.github.andreyasadchy.xtra.ui.chat

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.model.LoggedIn
import com.github.andreyasadchy.xtra.model.User
import com.github.andreyasadchy.xtra.model.chat.*
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.TwitchService
import com.github.andreyasadchy.xtra.ui.common.BaseViewModel
import com.github.andreyasadchy.xtra.ui.player.ChatReplayManager
import com.github.andreyasadchy.xtra.ui.view.chat.ChatView
import com.github.andreyasadchy.xtra.ui.view.chat.MAX_ADAPTER_COUNT
import com.github.andreyasadchy.xtra.util.SingleLiveEvent
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.chat.*
import com.github.andreyasadchy.xtra.util.nullIfEmpty
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.collections.ArrayList
import kotlin.collections.Collection
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.asReversed
import kotlin.collections.associateBy
import kotlin.collections.chunked
import kotlin.collections.contains
import kotlin.collections.containsKey
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.collections.hashSetOf
import kotlin.collections.isNotEmpty
import kotlin.collections.isNullOrEmpty
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.set
import kotlin.collections.sortedBy

class ChatViewModel @Inject constructor(
        private val repository: TwitchService,
        private val playerRepository: PlayerRepository) : BaseViewModel(), ChatView.MessageSenderCallback {

    val recentEmotes: LiveData<List<Emote>> by lazy {
        MediatorLiveData<List<Emote>>().apply {
            addSource(emotesFromSets) { twitch ->
                removeSource(emotesFromSets)
                addSource(_otherEmotes) { other ->
                    removeSource(_otherEmotes)
                    addSource(playerRepository.loadRecentEmotes()) { recent ->
                        value = recent.filter { (twitch.contains<Emote>(it) || other.contains(it)) }
                    }
                }
            }
        }
    }
    private val _otherEmotes = MutableLiveData<List<Emote>>()
    val otherEmotes: LiveData<List<Emote>>
        get() = _otherEmotes

    val recentMessages = MutableLiveData<List<LiveChatMessage>>()
    val globalBadges = MutableLiveData<List<TwitchBadge>?>()
    val channelBadges = MutableLiveData<List<TwitchBadge>>()
    val cheerEmotes = MutableLiveData<List<CheerEmote>>()
    var emoteSetsAdded = false
    val emotesFromSets = MutableLiveData<List<Emote>>()
    val emotesLoaded = MutableLiveData<Boolean>()
    val roomState = MutableLiveData<RoomState>()
    val command = MutableLiveData<Command>()
    val reward = MutableLiveData<ChatMessage>()

    private val _chatMessages by lazy {
        MutableLiveData<MutableList<ChatMessage>>().apply { value = Collections.synchronizedList(ArrayList(MAX_ADAPTER_COUNT + 1)) }
    }
    val chatMessages: LiveData<MutableList<ChatMessage>>
        get() = _chatMessages
    private val _newMessage by lazy { MutableLiveData<ChatMessage>() }
    val newMessage: LiveData<ChatMessage>
        get() = _newMessage

    var chat: ChatController? = null

    private val _newChatter by lazy { SingleLiveEvent<Chatter>() }
    val newChatter: LiveData<Chatter>
        get() = _newChatter

    val chatters: Collection<Chatter>
        get() = (chat as LiveChatController).chatters.values

    fun startLive(useSSl: Boolean, usePubSub: Boolean, user: User, helixClientId: String?, gqlClientId: String, channelId: String?, channelLogin: String?, channelName: String?, showUserNotice: Boolean, showClearMsg: Boolean, showClearChat: Boolean, enableRecentMsg: Boolean? = false, recentMsgLimit: String? = null) {
        if (chat == null && channelLogin != null && channelName != null) {
            chat = LiveChatController(
                useSSl = useSSl,
                usePubSub = usePubSub,
                user = user,
                helixClientId = helixClientId,
                channelId = channelId,
                channelLogin = channelLogin,
                displayName = channelName,
                showUserNotice = showUserNotice,
                showClearMsg = showClearMsg,
                showClearChat = showClearChat
            )
            if (channelId != null) {
                init(
                    helixClientId = helixClientId,
                    helixToken = user.helixToken?.nullIfEmpty(),
                    gqlClientId = gqlClientId,
                    channelId = channelId,
                    channelLogin = channelLogin,
                    enableRecentMsg = enableRecentMsg,
                    recentMsgLimit = recentMsgLimit
                )
            }
        }
    }

    fun startReplay(user: User, helixClientId: String?, gqlClientId: String, channelId: String?, videoId: String, startTime: Double, getCurrentPosition: () -> Double) {
        if (chat == null) {
            chat = VideoChatController(
                clientId = gqlClientId,
                videoId = videoId,
                startTime = startTime,
                getCurrentPosition = getCurrentPosition
            )
            if (channelId != null) {
                init(
                    helixClientId = helixClientId,
                    helixToken = user.helixToken?.nullIfEmpty(),
                    gqlClientId = gqlClientId,
                    channelId = channelId
                )
            }
        }
    }

    fun start() {
        chat?.start()
    }

    fun stop() {
        chat?.pause()
    }

    override fun send(message: CharSequence) {
        chat?.send(message)
    }

    override fun onCleared() {
        chat?.stop()
        super.onCleared()
    }

    private fun init(helixClientId: String?, helixToken: String?, gqlClientId: String, channelId: String, channelLogin: String? = null, enableRecentMsg: Boolean? = false, recentMsgLimit: String? = null) {
        chat?.start()
        val list = mutableListOf<Emote>()
        savedGlobalBadges.also {
            if (!it.isNullOrEmpty()) {
                globalBadges.value = it
                emotesLoaded.value = true
            } else {
                viewModelScope.launch {
                    try {
                        playerRepository.loadGlobalBadges().body()?.badges?.let { badges ->
                            if (badges.isNotEmpty()) {
                                savedGlobalBadges = badges
                                globalBadges.value = badges
                                emotesLoaded.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global badges", e)
                    }
                }
            }
        }
        globalStvEmotes.also {
            if (!it.isNullOrEmpty()) {
                (chat as? LiveChatController)?.addEmotes(it)
                list.addAll(it)
                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                emotesLoaded.value = true
            } else {
                viewModelScope.launch {
                    try {
                        playerRepository.loadGlobalStvEmotes().body()?.emotes?.let { emotes ->
                            if (emotes.isNotEmpty()) {
                                globalStvEmotes = emotes
                                (chat as? LiveChatController)?.addEmotes(emotes)
                                list.addAll(emotes)
                                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                                emotesLoaded.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global 7tv emotes", e)
                    }
                }
            }
        }
        globalBttvEmotes.also {
            if (!it.isNullOrEmpty()) {
                (chat as? LiveChatController)?.addEmotes(it)
                list.addAll(it)
                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                emotesLoaded.value = true
            } else {
                viewModelScope.launch {
                    try {
                        playerRepository.loadGlobalBttvEmotes().body()?.emotes?.let { emotes ->
                            if (emotes.isNotEmpty()) {
                                globalBttvEmotes = emotes
                                (chat as? LiveChatController)?.addEmotes(emotes)
                                list.addAll(emotes)
                                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                                emotesLoaded.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global BTTV emotes", e)
                    }
                }
            }
        }
        globalFfzEmotes.also {
            if (!it.isNullOrEmpty()) {
                (chat as? LiveChatController)?.addEmotes(it)
                list.addAll(it)
                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                emotesLoaded.value = true
            } else {
                viewModelScope.launch {
                    try {
                        playerRepository.loadBttvGlobalFfzEmotes().body()?.emotes?.let { emotes ->
                            if (emotes.isNotEmpty()) {
                                globalFfzEmotes = emotes
                                (chat as? LiveChatController)?.addEmotes(emotes)
                                list.addAll(emotes)
                                _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                                emotesLoaded.value = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load global FFZ emotes", e)
                    }
                }
            }
        }
        if (channelLogin != null && enableRecentMsg == true && recentMsgLimit != null) {
            viewModelScope.launch {
                try {
                    playerRepository.loadRecentMessages(channelLogin, recentMsgLimit).body()?.messages?.let {
                        if (it.isNotEmpty()) {
                            recentMessages.postValue(it)
                            emotesLoaded.value = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load recent messages for channel $channelLogin", e)
                }
            }
        }
        viewModelScope.launch {
            try {
                playerRepository.loadChannelBadges(channelId).body()?.badges?.let {
                    if (it.isNotEmpty()) {
                        channelBadges.postValue(it)
                        emotesLoaded.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load badges for channel $channelId", e)
            }
        }
        viewModelScope.launch {
            try {
                playerRepository.loadStvEmotes(channelId).body()?.emotes?.let {
                    if (it.isNotEmpty()) {
                        (chat as? LiveChatController)?.addEmotes(it)
                        list.addAll(it)
                        _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                        emotesLoaded.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load 7tv emotes for channel $channelId", e)
            }
        }
        viewModelScope.launch {
            try {
                playerRepository.loadBttvEmotes(channelId).body()?.emotes?.let {
                    if (it.isNotEmpty()) {
                        (chat as? LiveChatController)?.addEmotes(it)
                        list.addAll(it)
                        _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                        emotesLoaded.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load BTTV emotes for channel $channelId", e)
            }
        }
        viewModelScope.launch {
            try {
                playerRepository.loadBttvFfzEmotes(channelId).body()?.emotes?.let {
                    if (it.isNotEmpty()) {
                        (chat as? LiveChatController)?.addEmotes(it)
                        list.addAll(it)
                        _otherEmotes.value = list.sortedBy { emote -> emote is StvEmote }.sortedBy { emote -> emote is BttvEmote }.sortedBy { emote -> emote is FfzEmote }
                        emotesLoaded.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load FFZ emotes for channel $channelId", e)
            }
        }
        viewModelScope.launch {
            try {
                repository.loadCheerEmotes(channelId, helixClientId, helixToken, gqlClientId)?.let {
                    if (it.isNotEmpty()) {
                        cheerEmotes.postValue(it)
                        emotesLoaded.value = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cheermotes for channel $channelId", e)
            }
        }
    }

    inner class LiveChatController(
            private val useSSl: Boolean,
            private val usePubSub: Boolean,
            private val user: User,
            private val helixClientId: String?,
            private val channelId: String?,
            private val channelLogin: String,
            displayName: String,
            private val showUserNotice: Boolean,
            private val showClearMsg: Boolean,
            private val showClearChat: Boolean) : ChatController(), OnUserStateReceivedListener, OnRoomStateReceivedListener, OnCommandReceivedListener, OnRewardReceivedListener {

        private var chat: LiveChatThread? = null
        private var loggedInChat: LoggedInChatThread? = null
        private var pubSub: PubSubWebSocket? = null
        private val allEmotesMap = mutableMapOf<String, Emote>()

        val chatters = ConcurrentHashMap<String?, Chatter>()

        init {
            chatters[displayName] = Chatter(displayName)
        }

        override fun send(message: CharSequence) {
            if (message.toString() == "/dc" || message.toString() == "/disconnect") {
                disconnect()
            } else {
                loggedInChat?.send(message)
                val usedEmotes = hashSetOf<RecentEmote>()
                val currentTime = System.currentTimeMillis()
                message.split(' ').forEach { word ->
                    allEmotesMap[word]?.let { usedEmotes.add(RecentEmote(word, it.url, currentTime)) }
                }
                if (usedEmotes.isNotEmpty()) {
                    playerRepository.insertRecentEmotes(usedEmotes)
                }
            }
        }

        override fun start() {
            pause()
            chat = TwitchApiHelper.startChat(useSSl, user is LoggedIn, channelLogin, showUserNotice, showClearMsg, showClearChat, usePubSub, this, this, this, this, this)
            if (user is LoggedIn) {
                loggedInChat = TwitchApiHelper.startLoggedInChat(useSSl, user.login, user.gqlToken?.nullIfEmpty() ?: user.helixToken, channelLogin, showUserNotice, showClearMsg, showClearChat, usePubSub, this, this, this, this, this)
            }
            if (usePubSub && !channelId.isNullOrBlank()) {
                pubSub = TwitchApiHelper.startPubSub(channelId, viewModelScope, this, this)
            }
        }

        override fun pause() {
            chat?.disconnect()
            loggedInChat?.disconnect()
            pubSub?.disconnect()
        }

        override fun stop() {
            pause()
        }

        override fun onMessage(message: ChatMessage) {
            super.onMessage(message)
            if (message.userName != null && !chatters.containsKey(message.userName)) {
                val chatter = Chatter(message.userName)
                chatters[message.userName] = chatter
                _newChatter.postValue(chatter)
            }
        }

        override fun onUserState(sets: List<String>?) {
            if (helixClientId != null && user.helixToken?.nullIfEmpty() != null) {
                if (savedEmoteSets != sets) {
                    viewModelScope.launch {
                        val emotes = mutableListOf<Emote>()
                        sets?.asReversed()?.chunked(25)?.forEach {
                            try {
                                val list = repository.loadEmotesFromSet(helixClientId, user.helixToken, it)
                                if (list != null) {
                                    emotes.addAll(list)
                                }
                            } catch (e: Exception) {
                            }
                        }
                        if (emotes.isNotEmpty()) {
                            savedEmoteSets = sets
                            savedEmotesFromSets = emotes
                            emoteSetsAdded = true
                            val items = emotes.filter { it.ownerId == channelId }
                            for (item in items.asReversed()) {
                                emotes.add(0, item)
                            }
                            addEmotes(emotes)
                            emotesFromSets.value = emotes
                        }
                    }
                } else {
                    if (!emoteSetsAdded) {
                        val emotes = mutableListOf<Emote>()
                        savedEmotesFromSets?.let { emotes.addAll(it) }
                        if (emotes.isNotEmpty()) {
                            emoteSetsAdded = true
                            val items = emotes.filter { it.ownerId == channelId }
                            for (item in items.asReversed()) {
                                emotes.add(0, item)
                            }
                            addEmotes(emotes)
                            viewModelScope.launch {
                                try {
                                    emotesFromSets.value = emotes!!
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onRoomState(list: RoomState) {
            roomState.postValue(list)
        }

        override fun onCommand(list: Command) {
            command.postValue(list)
        }

        override fun onReward(message: ChatMessage) {
            reward.postValue(message)
        }

        fun addEmotes(list: List<Emote>) {
            if (user is LoggedIn) {
                allEmotesMap.putAll(list.associateBy { it.name })
            }
        }

        fun isActive(): Boolean? {
            return chat?.isActive
        }

        fun disconnect() {
            if (chat?.isActive == true) {
                chat?.disconnect()
                loggedInChat?.disconnect()
                pubSub?.disconnect()
                roomState.postValue(RoomState(null, null, null, null, null))
                command.postValue(Command(type = "disconnect_command"))
            }
        }
    }

    private inner class VideoChatController(
            private val clientId: String,
            private val videoId: String,
            private val startTime: Double,
            private val getCurrentPosition: () -> Double) : ChatController() {

        private var chatReplayManager: ChatReplayManager? = null

        override fun send(message: CharSequence) {

        }

        override fun start() {
            stop()
            chatReplayManager = ChatReplayManager(clientId, repository, videoId, startTime, getCurrentPosition, this, { _chatMessages.postValue(ArrayList()) }, viewModelScope)
        }

        override fun pause() {
            chatReplayManager?.stop()
        }

        override fun stop() {
            chatReplayManager?.stop()
        }
    }

    abstract inner class ChatController : OnChatMessageReceivedListener {
        abstract fun send(message: CharSequence)
        abstract fun start()
        abstract fun pause()
        abstract fun stop()

        override fun onMessage(message: ChatMessage) {
            _chatMessages.value!!.add(message)
            _newMessage.postValue(message)
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"

        private var savedEmoteSets: List<String>? = null
        private var savedEmotesFromSets: List<Emote>? = null
        private var savedGlobalBadges: List<TwitchBadge>? = null
        private var globalStvEmotes: List<StvEmote>? = null
        private var globalBttvEmotes: List<BttvEmote>? = null
        private var globalFfzEmotes: List<FfzEmote>? = null
    }
}