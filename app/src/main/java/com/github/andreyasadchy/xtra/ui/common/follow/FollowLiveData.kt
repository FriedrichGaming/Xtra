package com.github.andreyasadchy.xtra.ui.common.follow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.lifecycle.MutableLiveData
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.model.User
import com.github.andreyasadchy.xtra.model.offline.LocalFollowChannel
import com.github.andreyasadchy.xtra.model.offline.LocalFollowGame
import com.github.andreyasadchy.xtra.repository.LocalFollowChannelRepository
import com.github.andreyasadchy.xtra.repository.LocalFollowGameRepository
import com.github.andreyasadchy.xtra.repository.TwitchService
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DownloadUtils
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class FollowLiveData(
    private val localFollowsChannel: LocalFollowChannelRepository? = null,
    private val localFollowsGame: LocalFollowGameRepository? = null,
    private val userId: String?,
    private val userLogin: String?,
    private val userName: String?,
    private var channelLogo: String?,
    private val repository: TwitchService,
    private val helixClientId: String? = null,
    private val user: User,
    private val gqlClientId: String? = null,
    private val viewModelScope: CoroutineScope) : MutableLiveData<Boolean>()  {

    init {
        viewModelScope.launch {
            try {
                // val isFollowing = userId?.let { repository.loadUserFollows(clientId, user.token, it, user.id) }
                val isLocalFollowing = userId?.let {
                    if (localFollowsGame != null) {
                        localFollowsGame.getFollowById(it)
                    } else {
                        localFollowsChannel?.getFollowById(it)
                    }
                } != null
                super.setValue(isLocalFollowing)
            } catch (e: Exception) {

            }
        }
    }

    fun saveFollowChannel(context: Context) {
        GlobalScope.launch {
            try {
                if (userId != null) {
                    try {
                        Glide.with(context)
                            .asBitmap()
                            .load(channelLogo)
                            .into(object: CustomTarget<Bitmap>() {
                                override fun onLoadCleared(placeholder: Drawable?) {

                                }

                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    DownloadUtils.savePng(context, "profile_pics", userId, resource)
                                }
                            })
                    } catch (e: Exception) {

                    }
                    val downloadedLogo = File(context.filesDir.toString() + File.separator + "profile_pics" + File.separator + "${userId}.png").absolutePath
                    localFollowsChannel?.saveFollow(LocalFollowChannel(userId, userLogin, userName, downloadedLogo))
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteFollowChannel(context: Context) {
        viewModelScope.launch {
            try {
                if (userId != null) {
                    localFollowsChannel?.getFollowById(userId)?.let { localFollowsChannel.deleteFollow(context, it) }
                }
            } catch (e: Exception) {

            }
        }
    }

    fun saveFollowGame(context: Context) {
        GlobalScope.launch {
            try {
                if (userId != null) {
                    if (channelLogo == null) {
                        val get = if (context.prefs().getBoolean(C.API_USEHELIX, true) && context.prefs().getString(C.USERNAME, "") != "") {
                            repository.loadGame(helixClientId, user.token, userId)?.boxArt
                        } else {
                            repository.loadGameBoxArtGQLQuery(gqlClientId, userId)
                        }
                        if (get != null) {
                            channelLogo = TwitchApiHelper.getTemplateUrl(get, "game")
                        }
                    }
                    try {
                        Glide.with(context)
                            .asBitmap()
                            .load(channelLogo)
                            .into(object: CustomTarget<Bitmap>() {
                                override fun onLoadCleared(placeholder: Drawable?) {

                                }

                                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                    DownloadUtils.savePng(context, "box_art", userId, resource)
                                }
                            })
                    } catch (e: Exception) {

                    }
                    val downloadedLogo = File(context.filesDir.toString() + File.separator + "box_art" + File.separator + "${userId}.png").absolutePath
                    localFollowsGame?.saveFollow(LocalFollowGame(userId, userName, downloadedLogo))
                }
            } catch (e: Exception) {

            }
        }
    }

    fun deleteFollowGame(context: Context) {
        viewModelScope.launch {
            try {
                if (userId != null) {
                    localFollowsGame?.getFollowById(userId)?.let { localFollowsGame.deleteFollow(context, it) }
                }
            } catch (e: Exception) {

            }
        }
    }
}