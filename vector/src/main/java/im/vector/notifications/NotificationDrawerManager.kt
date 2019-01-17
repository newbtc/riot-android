/*
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.notifications

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.support.v4.app.NotificationCompat
import android.text.TextUtils
import android.view.WindowManager
import im.vector.Matrix
import im.vector.VectorApp
import org.matrix.androidsdk.util.Log
import java.io.*


data class RoomEventGroupInfo(
        val roomId: String
) {
    var roomDisplayName: String = ""
    var roomAvatarPath: String? = null
    var hasNewEvent: Boolean = false //An event in the list has not yet been display
    var shouldBing: Boolean = false //true if at least one on the not yet displayed event is noisy
    var customSound: String? = null
    var hasSmartReplyError = false
}

class NotificationDrawerManager(val context: Context) {

    init {
        loadEventInfo()
    }

    private lateinit var eventList: MutableList<NotifiableEvent>
    private var myUserDisplayName: String = ""

    //Keeps a mapping between a notification ID
    //and the list of eventIDs represented by the notification
    //private val notificationToEventsMap: MutableMap<String, List<String>> = HashMap()
    //private val notificationToRoomIdMap: MutableMap<Int, String> = HashMap()

    private var currentRoomId: String? = null


    /*
    * Should be called as soon as a new event is ready to be displayed.
    * The notification corresponding to this event will not be displayed until
    * #refreshNotificationDrawer() is called.
    * Events might be grouped and there might not be one notification per event!
    *
     */
    fun onNotifiableEventReceived(notifiableEvent: NotifiableEvent, userId: String, userDisplayName: String?) {
        //If we support multi session, event list should be per userId
        //Currently only manage single session
        Log.d(LOG_TAG, "%%%%%%%% onNotifiableEventReceived $notifiableEvent")
        synchronized(this) {
            myUserDisplayName = userDisplayName ?: userId
            val existing = eventList.firstOrNull { it.eventId == notifiableEvent.eventId }
            if (existing != null) {
                if (existing.isPushGatewayEvent) {
                    //Use the event coming from the event stream as it may contains more info than
                    //the fcm one (like type/content/clear text)
                    // In this case the message has already been notified, and might have done some noise
                    // So we want the notification to be updated even if it has already been displayed
                    // But it should make no noise (e.g when an encrypted message from FCM should be
                    // update with clear text after a sync)
                    notifiableEvent.hasBeenDisplayed = false
                    notifiableEvent.noisy = false
                    eventList.remove(existing)
                    eventList.add(notifiableEvent)

                } else {
                    //keep the existing one, do not replace
                }
            } else {
                eventList.add(notifiableEvent)
            }

        }
    }

    /*
    * Clear all known events and refresh the notification drawer
     */
    fun clearAllEvents() {
        synchronized(this) {
            eventList.clear()
        }
        refreshNotificationDrawer()
    }

    /*
    * Clear all known message events for this room and refresh the notification drawer
     */
    fun clearMessageEventOfRoom(roomId: String?) {
        if (roomId != null) {
            eventList.removeAll { e ->
                if (e is NotifiableMessageEvent) {
                    return@removeAll e.roomId == roomId
                }
                return@removeAll false
            }
            NotificationUtils.cancelNotificationMessage(context, roomId, ROOM_MESSAGES_NOTIFICATION_ID)
        }
        refreshNotificationDrawer()
    }

    /**
     * Should be called when the application is currently opened and showing timeline for the given roomId.
     * Used to ignore events related to that room (no need to display notification) and clean any existing notification on this room.
     */
    fun setCurrentRoom(roomId: String?) {
        var hasChanged = false
        synchronized(this) {
            hasChanged = roomId != currentRoomId
            currentRoomId = roomId
        }
        if (hasChanged) {
            clearMessageEventOfRoom(roomId)
        }
    }

    public fun homeActivityDidResume(matrixID: String?) {
        synchronized(this) {
            eventList.removeAll { e ->
                return@removeAll !(e is NotifiableMessageEvent) //messages are cleared when entreing room
            }
        }
    }


    fun refreshNotificationDrawer() {
        synchronized(this) {

            Log.d(LOG_TAG, "%%%%%%%% REFRESH NOTIFICATION DRAWER ")
            //TMP code
            var hasNewEvent = false
            var summaryIsNoisy = false
            val summaryInboxStyle = NotificationCompat.InboxStyle()

            //group events by room to create a single MessagingStyle notif
            val roomIdToEventMap: MutableMap<String, ArrayList<NotifiableMessageEvent>> = HashMap()
            val simpleEvents: ArrayList<NotifiableEvent> = ArrayList()
            val notifications: ArrayList<Notification> = ArrayList()

            for (event in eventList) {
                if (event is NotifiableMessageEvent) {
                    val roomId = event.roomId
                    if (!shouldIgnoreMessageEventInRoom(roomId)) {
                        var roomEvents = roomIdToEventMap[roomId]
                        if (roomEvents == null) {
                            roomEvents = ArrayList()
                            roomIdToEventMap[roomId] = roomEvents
                        }
                        roomEvents.add(event)
                    }
                } else {
                    simpleEvents.add(event)
                }
            }


            Log.d(LOG_TAG, "%%%%%%%% REFRESH NOTIFICATION DRAWER ${roomIdToEventMap.size} room groups")

            //events have been grouped
            for ((roomId, events) in roomIdToEventMap) {
                val roomGroup = RoomEventGroupInfo(roomId)
                roomGroup.hasNewEvent = false
                roomGroup.shouldBing = false
                val senderDisplayName = events[0].senderName ?: ""
                val roomName = events[0].roomName ?: events[0].senderName ?: ""
                val style = NotificationCompat.MessagingStyle(myUserDisplayName)
                roomGroup.roomDisplayName = roomName
                if (roomName != senderDisplayName) {
                    style.conversationTitle = roomName
                }

                val largeBitmap = getRoomBitmap(events)


                for (event in events) {
                    //if all events in this room have already been displayed there is no need to update it
                    if (!event.hasBeenDisplayed) {
                        roomGroup.shouldBing = roomGroup.shouldBing || event.noisy
                        roomGroup.customSound = event.soundName
                    }
                    roomGroup.hasNewEvent = roomGroup.hasNewEvent || !event.hasBeenDisplayed
                    //TODO update to compat-28 in order to support media and sender as a Person object
                    if (event.outGoingMessage && event.outGoingMessageFailed) {
                        style.addMessage("** Failed to send - please open room", event.timestamp, event.senderName)
                        roomGroup.hasSmartReplyError = true
                    } else {
                        style.addMessage(event.body, event.timestamp, event.senderName)
                    }
                    event.hasBeenDisplayed = true //we can consider it as displayed
                }

                summaryInboxStyle.addLine("${roomGroup.roomDisplayName}: ${events.size} notification(s)")

                if (roomGroup.hasNewEvent) { //Should update displayed notification
                    Log.d(LOG_TAG, "%%%%%%%% REFRESH NOTIFICATION DRAWER $roomId need refresh")
                    NotificationUtils.buildMessagesListNotification(context, style, roomGroup, largeBitmap, myUserDisplayName)?.let {
                        //is there an id for this room?
                        notifications.add(it)
                        NotificationUtils.showNotificationMessage(context, roomId, ROOM_MESSAGES_NOTIFICATION_ID, it)
                    }
                    hasNewEvent = true
                    summaryIsNoisy = summaryIsNoisy || roomGroup.shouldBing
                } else {
                    Log.d(LOG_TAG, "%%%%%%%% REFRESH NOTIFICATION DRAWER $roomId is up to date")
                }
            }


            //Handle simple events
            for (event in simpleEvents) {
                //We build a simple event
                if (!event.hasBeenDisplayed) {
                    NotificationUtils.buildSimpleEventNotification(context, event, null, myUserDisplayName)?.let {
                        notifications.add(it)
                        NotificationUtils.showNotificationMessage(context, event.eventId, ROOM_EVENT_NOTIFICATION_ID, it)
                        event.hasBeenDisplayed = true //we can consider it as displayed
                        hasNewEvent = true
                        summaryIsNoisy = summaryIsNoisy || event.noisy
                        summaryInboxStyle.addLine(event.description)
                    }
                }
            }


            //======== Build summary notification =========
            //On Android 7.0 (API level 24) and higher, the system automatically builds a summary for
            // your group using snippets of text from each notification. The user can expand this
            // notification to see each separate notification, as shown in figure 1.
            // To support older versions, which cannot show a nested group of notifications,
            // you must create an extra notification that acts as the summary.
            // This appears as the only notification and the system hides all the others.
            // So this summary should include a snippet from all the other notifications,
            // which the user can tap to open your app.
            // The behavior of the group summary may vary on some device types such as wearables.
            // To ensure the best experience on all devices and versions, always include a group summary when you create a group
            // https://developer.android.com/training/notify-user/group

            if (eventList.isEmpty()) {
                NotificationUtils.cancelNotificationMessage(context, null, SUMMARY_NOTIFICATION_ID)
            } else {
                val nbEvents = roomIdToEventMap.size + simpleEvents.size
                summaryInboxStyle.setBigContentTitle("$nbEvents notifications")
                NotificationUtils.buildSummaryListNotification(
                        context,
                        summaryInboxStyle, "$nbEvents notifications",
                        noisy = hasNewEvent && summaryIsNoisy
                )?.let {
                    NotificationUtils.showNotificationMessage(context, null, SUMMARY_NOTIFICATION_ID, it)
                }

                if (hasNewEvent && summaryIsNoisy) {
                    try {
                        // turn the screen on for 3 seconds
                        if (Matrix.getInstance(VectorApp.getInstance())!!.pushManager.isScreenTurnedOn) {
                            val pm = VectorApp.getInstance().getSystemService(Context.POWER_SERVICE) as PowerManager
                            val wl = pm.newWakeLock(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or PowerManager.ACQUIRE_CAUSES_WAKEUP, "riot:manageNotificationSound")
                            wl.acquire(3000)
                            wl.release()
                        }
                    } catch (e : Throwable) {
                        Log.e(LOG_TAG, "## Failed to turn screen on", e)
                    }

                }
            }
            //notice that we can get bit out of sync with actual display but not a big issue
        }
    }

    private fun getRoomBitmap(events: ArrayList<NotifiableMessageEvent>): Bitmap? {
        if (events.isEmpty()) return null

        //Use the last event (most recent?)
        val roomAvatarPath = events[events.size - 1].roomAvatarPath
                ?: events[events.size - 1].senderAvatarPath
        if (!TextUtils.isEmpty(roomAvatarPath)) {
            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            try {
                return BitmapFactory.decodeFile(roomAvatarPath, options)
            } catch (oom: OutOfMemoryError) {
                Log.e(LOG_TAG, "decodeFile failed with an oom", oom)
            }

        }
        return null
    }

    private fun shouldIgnoreMessageEventInRoom(roomId: String?): Boolean {
        return roomId != null && roomId == currentRoomId
    }


    fun persistInfo() {
        if (eventList.isEmpty()) {
            deleteCachedRoomNotifications(context)
            return
        }
        try {
            val file = File(context.applicationContext.cacheDir, ROOMS_NOTIFICATIONS_FILE_NAME)
            val fileOut = FileOutputStream(file)
            val out = ObjectOutputStream(fileOut)
            out.writeObject(eventList)
            out.close()
            fileOut.close()
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "## Failed to save cached notification info", e)
        }
    }

    private fun loadEventInfo() {
        try {
            val file = File(context.applicationContext.cacheDir, ROOMS_NOTIFICATIONS_FILE_NAME)
            if (file.exists()) {
                val fileIn = FileInputStream(file)
                val ois = ObjectInputStream(fileIn)
                val readObject = ois.readObject()
                (readObject as? ArrayList<*>)?.let {
                    this.eventList = ArrayList(it.filter { it is NotifiableEvent }.map { it as NotifiableEvent })
                }
            } else {
                this.eventList = ArrayList()
            }
        } catch (e: Throwable) {
            this.eventList = ArrayList()
            Log.e(LOG_TAG, "## Failed to load cached notification info", e)
        }
    }

    private fun deleteCachedRoomNotifications(context: Context) {
        val file = File(context.applicationContext.cacheDir, ROOMS_NOTIFICATIONS_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }

    companion object {
        private const val SUMMARY_NOTIFICATION_ID = 0
        private const val ROOM_MESSAGES_NOTIFICATION_ID = 1
        private const val ROOM_EVENT_NOTIFICATION_ID = 2

        private const val ROOMS_NOTIFICATIONS_FILE_NAME = "im.vector.notifications.cache"
        private val LOG_TAG = NotificationDrawerManager::class.java.simpleName
    }
}