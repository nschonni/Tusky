package com.keylesspalace.tusky.components.account

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import at.connyduck.calladapter.networkresult.fold
import com.keylesspalace.tusky.appstore.BlockEvent
import com.keylesspalace.tusky.appstore.DomainMuteEvent
import com.keylesspalace.tusky.appstore.EventHub
import com.keylesspalace.tusky.appstore.MuteEvent
import com.keylesspalace.tusky.appstore.ProfileEditedEvent
import com.keylesspalace.tusky.appstore.UnfollowEvent
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.entity.Account
import com.keylesspalace.tusky.entity.Relationship
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.util.Error
import com.keylesspalace.tusky.util.Loading
import com.keylesspalace.tusky.util.Resource
import com.keylesspalace.tusky.util.Success
import com.keylesspalace.tusky.util.getDomain
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

class AccountViewModel @Inject constructor(
    private val mastodonApi: MastodonApi,
    private val eventHub: EventHub,
    accountManager: AccountManager
) : ViewModel() {

    val accountData = MutableLiveData<Resource<Account>>()
    val relationshipData = MutableLiveData<Resource<Relationship>>()

    val noteSaved = MutableLiveData<Boolean>()

    val isRefreshing = MutableLiveData<Boolean>().apply { value = false }
    private var isDataLoading = false

    lateinit var accountId: String
    var isSelf = false

    /** True if the viewed account has the same domain as the active account */
    var isFromOwnDomain = false

    private var noteUpdateJob: Job? = null

    private val activeAccount = accountManager.activeAccount!!

    init {
        viewModelScope.launch {
            eventHub.events.collect { event ->
                if (event is ProfileEditedEvent && event.newProfileData.id == accountData.value?.data?.id) {
                    accountData.postValue(Success(event.newProfileData))
                }
            }
        }
    }

    private fun obtainAccount(reload: Boolean = false) {
        if (accountData.value == null || reload) {
            isDataLoading = true
            accountData.postValue(Loading())

            viewModelScope.launch {
                mastodonApi.account(accountId)
                    .fold(
                        { account ->
                            accountData.postValue(Success(account))
                            isDataLoading = false
                            isRefreshing.postValue(false)

                            isFromOwnDomain = getDomain(account.url) == activeAccount.domain
                        },
                        { t ->
                            Log.w(TAG, "failed obtaining account", t)
                            accountData.postValue(Error(cause = t))
                            isDataLoading = false
                            isRefreshing.postValue(false)
                        }
                    )
            }
        }
    }

    private fun obtainRelationship(reload: Boolean = false) {
        if (relationshipData.value == null || reload) {
            relationshipData.postValue(Loading())

            viewModelScope.launch {
                mastodonApi.relationships(listOf(accountId))
                    .fold(
                        { relationships ->
                            relationshipData.postValue(if (relationships.isNotEmpty()) Success(relationships[0]) else Error())
                        },
                        { t ->
                            Log.w(TAG, "failed obtaining relationships", t)
                            relationshipData.postValue(Error(cause = t))
                        }
                    )
            }
        }
    }

    fun changeFollowState() {
        val relationship = relationshipData.value?.data
        if (relationship?.following == true || relationship?.requested == true) {
            changeRelationship(RelationShipAction.UNFOLLOW)
        } else {
            changeRelationship(RelationShipAction.FOLLOW)
        }
    }

    fun changeBlockState() {
        if (relationshipData.value?.data?.blocking == true) {
            changeRelationship(RelationShipAction.UNBLOCK)
        } else {
            changeRelationship(RelationShipAction.BLOCK)
        }
    }

    fun muteAccount(notifications: Boolean, duration: Int?) {
        changeRelationship(RelationShipAction.MUTE, notifications, duration)
    }

    fun unmuteAccount() {
        changeRelationship(RelationShipAction.UNMUTE)
    }

    fun changeSubscribingState() {
        val relationship = relationshipData.value?.data
        if (relationship?.notifying == true || /* Mastodon 3.3.0rc1 */
            relationship?.subscribing == true /* Pleroma */
        ) {
            changeRelationship(RelationShipAction.UNSUBSCRIBE)
        } else {
            changeRelationship(RelationShipAction.SUBSCRIBE)
        }
    }

    fun blockDomain(instance: String) {
        viewModelScope.launch {
            mastodonApi.blockDomain(instance).fold({
                eventHub.dispatch(DomainMuteEvent(instance))
                val relation = relationshipData.value?.data
                if (relation != null) {
                    relationshipData.postValue(Success(relation.copy(blockingDomain = true)))
                }
            }, { e ->
                Log.e(TAG, "Error muting $instance", e)
            })
        }
    }

    fun unblockDomain(instance: String) {
        viewModelScope.launch {
            mastodonApi.unblockDomain(instance).fold({
                val relation = relationshipData.value?.data
                if (relation != null) {
                    relationshipData.postValue(Success(relation.copy(blockingDomain = false)))
                }
            }, { e ->
                Log.e(TAG, "Error unmuting $instance", e)
            })
        }
    }

    fun changeShowReblogsState() {
        if (relationshipData.value?.data?.showingReblogs == true) {
            changeRelationship(RelationShipAction.FOLLOW, false)
        } else {
            changeRelationship(RelationShipAction.FOLLOW, true)
        }
    }

    /**
     * @param parameter showReblogs if RelationShipAction.FOLLOW, notifications if MUTE
     */
    private fun changeRelationship(
        relationshipAction: RelationShipAction,
        parameter: Boolean? = null,
        duration: Int? = null
    ) = viewModelScope.launch {
        val relation = relationshipData.value?.data
        val account = accountData.value?.data
        val isMastodon = relationshipData.value?.data?.notifying != null

        if (relation != null && account != null) {
            // optimistically post new state for faster response

            val newRelation = when (relationshipAction) {
                RelationShipAction.FOLLOW -> {
                    if (account.locked) {
                        relation.copy(requested = true)
                    } else {
                        relation.copy(following = true)
                    }
                }
                RelationShipAction.UNFOLLOW -> relation.copy(following = false)
                RelationShipAction.BLOCK -> relation.copy(blocking = true)
                RelationShipAction.UNBLOCK -> relation.copy(blocking = false)
                RelationShipAction.MUTE -> relation.copy(muting = true)
                RelationShipAction.UNMUTE -> relation.copy(muting = false)
                RelationShipAction.SUBSCRIBE -> {
                    if (isMastodon) {
                        relation.copy(notifying = true)
                    } else {
                        relation.copy(subscribing = true)
                    }
                }
                RelationShipAction.UNSUBSCRIBE -> {
                    if (isMastodon) {
                        relation.copy(notifying = false)
                    } else {
                        relation.copy(subscribing = false)
                    }
                }
            }
            relationshipData.postValue(Loading(newRelation))
        }

        val relationshipCall = when (relationshipAction) {
            RelationShipAction.FOLLOW -> mastodonApi.followAccount(
                accountId,
                showReblogs = parameter ?: true
            )
            RelationShipAction.UNFOLLOW -> mastodonApi.unfollowAccount(accountId)
            RelationShipAction.BLOCK -> mastodonApi.blockAccount(accountId)
            RelationShipAction.UNBLOCK -> mastodonApi.unblockAccount(accountId)
            RelationShipAction.MUTE -> mastodonApi.muteAccount(
                accountId,
                parameter ?: true,
                duration
            )
            RelationShipAction.UNMUTE -> mastodonApi.unmuteAccount(accountId)
            RelationShipAction.SUBSCRIBE -> {
                if (isMastodon) {
                    mastodonApi.followAccount(accountId, notify = true)
                } else {
                    mastodonApi.subscribeAccount(accountId)
                }
            }
            RelationShipAction.UNSUBSCRIBE -> {
                if (isMastodon) {
                    mastodonApi.followAccount(accountId, notify = false)
                } else {
                    mastodonApi.unsubscribeAccount(accountId)
                }
            }
        }

        relationshipCall.fold(
            { relationship ->
                relationshipData.postValue(Success(relationship))

                when (relationshipAction) {
                    RelationShipAction.UNFOLLOW -> eventHub.dispatch(UnfollowEvent(accountId))
                    RelationShipAction.BLOCK -> eventHub.dispatch(BlockEvent(accountId))
                    RelationShipAction.MUTE -> eventHub.dispatch(MuteEvent(accountId))
                    else -> { }
                }
            },
            { t ->
                Log.w(TAG, "failed loading relationship", t)
                relationshipData.postValue(Error(relation, cause = t))
            }
        )
    }

    fun noteChanged(newNote: String) {
        noteSaved.postValue(false)
        noteUpdateJob?.cancel()
        noteUpdateJob = viewModelScope.launch {
            delay(1500)
            mastodonApi.updateAccountNote(accountId, newNote)
                .fold(
                    {
                        noteSaved.postValue(true)
                        delay(4000)
                        noteSaved.postValue(false)
                    },
                    { t ->
                        Log.w(TAG, "Error updating note", t)
                    }
                )
        }
    }

    fun refresh() {
        reload(true)
    }

    private fun reload(isReload: Boolean = false) {
        if (isDataLoading) {
            return
        }
        accountId.let {
            obtainAccount(isReload)
            if (!isSelf) {
                obtainRelationship(isReload)
            }
        }
    }

    fun setAccountInfo(accountId: String) {
        this.accountId = accountId
        this.isSelf = activeAccount.accountId == accountId
        reload(false)
    }

    enum class RelationShipAction {
        FOLLOW, UNFOLLOW, BLOCK, UNBLOCK, MUTE, UNMUTE, SUBSCRIBE, UNSUBSCRIBE
    }

    companion object {
        const val TAG = "AccountViewModel"
    }
}
