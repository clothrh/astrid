/**
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.rmilk.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.text.TextUtils;

import com.flurry.android.FlurryAgent;
import com.timsu.astrid.R;
import com.todoroo.andlib.data.Property;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.data.Property.StringProperty;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.DialogUtilities;
import com.todoroo.astrid.api.SynchronizationProvider;
import com.todoroo.astrid.api.TaskContainer;
import com.todoroo.astrid.model.Task;
import com.todoroo.astrid.rmilk.MilkLoginActivity;
import com.todoroo.astrid.rmilk.Utilities;
import com.todoroo.astrid.rmilk.MilkLoginActivity.SyncLoginCallback;
import com.todoroo.astrid.rmilk.api.ApplicationInfo;
import com.todoroo.astrid.rmilk.api.ServiceImpl;
import com.todoroo.astrid.rmilk.api.ServiceInternalException;
import com.todoroo.astrid.rmilk.api.data.RtmList;
import com.todoroo.astrid.rmilk.api.data.RtmLists;
import com.todoroo.astrid.rmilk.api.data.RtmTask;
import com.todoroo.astrid.rmilk.api.data.RtmTaskList;
import com.todoroo.astrid.rmilk.api.data.RtmTaskSeries;
import com.todoroo.astrid.rmilk.api.data.RtmTasks;
import com.todoroo.astrid.rmilk.api.data.RtmAuth.Perms;
import com.todoroo.astrid.rmilk.api.data.RtmTask.Priority;
import com.todoroo.astrid.rmilk.data.MilkDataService;
import com.todoroo.astrid.service.AstridDependencyInjector;

public class RTMSyncProvider extends SynchronizationProvider<RTMTaskContainer> {

    private ServiceImpl rtmService = null;
    private String timeline = null;
    private MilkDataService dataService = null;

    static {
        AstridDependencyInjector.initialize();
    }

    @Autowired
    protected ExceptionService exceptionService;

    @Autowired
    protected DialogUtilities dialogUtilities;

    /** Temporary property for storing task tags */
    private static final StringProperty RTM_TAGS = new StringProperty(Task.TABLE,
            "tags"); //$NON-NLS-1$

    public RTMSyncProvider() {
        super();
        DependencyInjectionService.getInstance().inject(this);
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------------- public methods
    // ----------------------------------------------------------------------

    /**
     * Sign out of RTM, deleting all synchronization metadata
     */
    public void signOut() {
        Utilities.setToken(null);
        Utilities.clearLastSyncDate();

        dataService = MilkDataService.getInstance();
        dataService.clearMetadata();
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------------- authentication
    // ----------------------------------------------------------------------

    /**
     * Deal with a synchronization exception. If requested, will show an error
     * to the user (unless synchronization is happening in background)
     *
     * @param context
     * @param tag
     *            error tag
     * @param e
     *            exception
     * @param showErrorIfNeeded
     *            whether to display a dialog
     */
    private void handleRtmException(Context context, String tag, Exception e,
            boolean showErrorIfNeeded) {

        Utilities.setLastError(e.getMessage());

        // occurs when application was closed
        if(e instanceof IllegalStateException) {
            exceptionService.reportError(tag + "-caught", e); //$NON-NLS-1$

        // occurs when network error
        } else if(e instanceof ServiceInternalException &&
                ((ServiceInternalException)e).getEnclosedException() instanceof
                IOException) {
            Exception enclosedException = ((ServiceInternalException)e).getEnclosedException();
            exceptionService.reportError(tag + "-ioexception", enclosedException); //$NON-NLS-1$
            if(showErrorIfNeeded) {
                showError(context, enclosedException,
                        context.getString(R.string.rmilk_ioerror));
            }
        } else {
            if(e instanceof ServiceInternalException)
                e = ((ServiceInternalException)e).getEnclosedException();
            exceptionService.reportError(tag + "-unhandled", e); //$NON-NLS-1$
            if(showErrorIfNeeded)
                showError(context, e, null);
        }
    }

    @Override
    protected void initiate(Context context) {
        dataService = MilkDataService.getInstance();

        // authenticate the user. this will automatically call the next step
        authenticate(context);
    }

    /**
     * Perform authentication with RTM. Will open the SyncBrowser if necessary
     */
    @SuppressWarnings("nls")
    private void authenticate(final Context context) {
        final Resources r = context.getResources();
        FlurryAgent.onEvent("rtm-started");

        Utilities.recordSyncStart();

        try {
            String appName = null;
            String authToken = Utilities.getToken();
            String z = stripslashes(0,"q9883o3384n21snq17501qn38oo1r689", "b");
            String v = stripslashes(16,"19o2n020345219os","a");

            // check if we have a token & it works
            if(authToken != null) {
                rtmService = new ServiceImpl(new ApplicationInfo(
                        z, v, appName, authToken));
                if(!rtmService.isServiceAuthorized()) // re-do login
                    authToken = null;
            }

            if(authToken == null) {
                // try completing the authorization if it was partial
                if(rtmService != null) {
                    try {
                        String token = rtmService.completeAuthorization();
                        Utilities.setToken(token);
                        performSync(context);

                        return;
                    } catch (Exception e) {
                        // didn't work. do the process again.
                    }
                }

                // open up a dialog and have the user go to browser
                rtmService = new ServiceImpl(new ApplicationInfo(
                        z, v, appName));
                final String url = rtmService.beginAuthorization(Perms.delete);

                Intent intent = new Intent(context, MilkLoginActivity.class);
                MilkLoginActivity.setCallback(new SyncLoginCallback() {
                    public String verifyLogin(final Handler syncLoginHandler) {
                        if(rtmService == null) {
                            return null;
                        }

                        try {
                            String token = rtmService.completeAuthorization();
                            Utilities.setToken(token);
                            synchronize(context);
                            return null;
                        } catch (Exception e) {
                            // didn't work
                            exceptionService.reportError("rtm-verify-login", e);
                            rtmService = null;
                            if(e instanceof ServiceInternalException)
                                e = ((ServiceInternalException)e).getEnclosedException();
                            return r.getString(R.string.rmilk_MLA_error, e.getMessage());
                        }
                    }
                });
                intent.putExtra(MilkLoginActivity.URL_TOKEN, url);
                if(context instanceof Activity)
                    ((Activity)context).startActivityForResult(intent, 0);
                else
                    context.startActivity(intent);

            } else {
                performSync(context);
            }
        } catch (IllegalStateException e) {
        	// occurs when application was closed
        } catch (Exception e) {
            handleRtmException(context, "rtm-authenticate", e, true);
        }
    }

    // ----------------------------------------------------------------------
    // ----------------------------------------------------- synchronization!
    // ----------------------------------------------------------------------

    protected void performSync(final Context context) {
        try {
            // get RTM timeline
            timeline = rtmService.timelines_create();

            // load RTM lists
            RtmLists lists = rtmService.lists_getList();
            dataService.setLists(lists);

            // read all tasks
            ArrayList<TaskContainer> remoteChanges = new ArrayList<TaskContainer>();
            Date lastSyncDate = new Date(Utilities.getLastSyncDate());
            boolean shouldSyncIndividualLists = false;
            String filter = null;
            if(lastSyncDate.getTime() == 0)
                filter = "status:incomplete"; //$NON-NLS-1$ // 1st time sync: get unfinished tasks

            // try the quick synchronization
            try {
                Thread.sleep(2000); // throttle
                RtmTasks tasks = rtmService.tasks_getList(null, filter, lastSyncDate);
                addTasksToList(tasks, remoteChanges);
            } catch (Exception e) {
                handleRtmException(context, "rtm-quick-sync", e, false); //$NON-NLS-1$
                remoteChanges.clear();
                shouldSyncIndividualLists = true;
            }

            if(shouldSyncIndividualLists) {
                for(RtmList list : lists.getLists().values()) {
                    if(list.isSmart())
                        continue;
                    try {
                        Thread.sleep(1500);
                        RtmTasks tasks = rtmService.tasks_getList(list.getId(),
                                filter, lastSyncDate);
                        addTasksToList(tasks, remoteChanges);
                    } catch (Exception e) {
                        handleRtmException(context, "rtm-indiv-sync", e, true); //$NON-NLS-1$
                        continue;
                    }
                }
            }

            SyncData syncData = populateSyncData(remoteChanges);
            try {
                synchronizeTasks(syncData);
            } finally {
                syncData.localCreated.close();
                syncData.localUpdated.close();
            }

            Utilities.recordSuccessfulSync();

            FlurryAgent.onEvent("rtm-sync-finished"); //$NON-NLS-1$
        } catch (IllegalStateException e) {
        	// occurs when application was closed
        } catch (Exception e) {
            handleRtmException(context, "rtm-sync", e, true); //$NON-NLS-1$
        }
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------------- helper methods
    // ----------------------------------------------------------------------

    @Override
    protected String getNotificationTitle(Context context) {
        return context.getString(R.string.rmilk_notification_title);
    }

    // all synchronized properties
    private static final Property<?>[] PROPERTIES = new Property<?>[] {
            Task.ID,
            Task.TITLE,
            Task.IMPORTANCE,
            Task.DUE_DATE,
            Task.CREATION_DATE,
            Task.COMPLETION_DATE,
            Task.DELETION_DATE,
    };

    /**
     * Populate SyncData data structure
     */
    private SyncData populateSyncData(ArrayList<TaskContainer> remoteTasks) {
        // fetch locally created tasks
        TodorooCursor<Task> localCreated = dataService.getLocallyCreated(PROPERTIES);

        // fetch locally updated tasks
        TodorooCursor<Task> localUpdated = dataService.getLocallyUpdated(PROPERTIES);

        return new SyncData(remoteTasks, localCreated, localUpdated);
    }

    /**
     * Add the tasks read from RTM to the given list
     */
    private void addTasksToList(RtmTasks tasks, ArrayList<TaskContainer> list) {
        for (RtmTaskList taskList : tasks.getLists()) {
            for (RtmTaskSeries taskSeries : taskList.getSeries()) {
                TaskContainer remoteTask = parseRemoteTask(taskSeries);
                dataService.updateFromLocalCopy(task);
                list.add(remoteTask);
            }
        }
    }

    /**
     * Determine whether this task's property should be transmitted
     * @param task task to consider
     * @param property property to consider
     * @param remoteTask remote task proxy
     * @return
     */
    private boolean shouldTransmit(TaskContainer task, Property<?> property, TaskContainer remoteTask) {
        if(!task.task.containsValue(property))
            return false;

        if(remoteTask == null)
            return true;
        if(!remoteTask.task.containsValue(property))
            return true;
        return !AndroidUtilities.equals(task.task.getValue(property),
                remoteTask.task.getValue(property));
    }

    @Override
    protected void create(RTMTaskContainer task) throws IOException {
        RtmTaskSeries rtmTask = rtmService.tasks_add(timeline, task.listId,
                task.task.getValue(Task.TITLE));
        RTMTaskContainer newRemoteTask = parseRemoteTask(rtmTask);
        transferIdentifiers(newRemoteTask, task);
        push(task, newRemoteTask);
    }

    /**
     * Send changes for the given Task across the wire. If a remoteTask is
     * supplied, we attempt to intelligently only transmit the values that
     * have changed.
     */
    @Override
    protected void push(RTMTaskContainer local, RTMTaskContainer remote) throws IOException {
        // fetch remote task for comparison
        if(remote == null)
            remote = pull(local);

        if(shouldTransmit(local, Task.TITLE, remote))
            rtmService.tasks_setName(timeline, local.listId, local.taskSeriesId,
                    local.taskId, local.getValue(Task.TITLE));
        if(shouldTransmit(local, Task.IMPORTANCE, remote))
            rtmService.tasks_setPriority(timeline, local.listId, local.taskSeriesId,
                    local.taskId, Priority.values()[local.task.getValue(Task.IMPORTANCE)]);
        if(shouldTransmit(local, Task.DUE_DATE, remote))
            rtmService.tasks_setDueDate(timeline, local.listId, local.taskSeriesId,
                    local.taskId, DateUtilities.unixtimeToDate(local.task.getValue(Task.DUE_DATE)),
                    local.task.hasDueTime());
        if(shouldTransmit(local, Task.COMPLETION_DATE, remote)) {
            if(local.task.getValue(Task.COMPLETION_DATE) == 0)
                rtmService.tasks_uncomplete(timeline, local.listId, local.taskSeriesId,
                        local.taskId);
            else
                rtmService.tasks_complete(timeline, local.listId, local.taskSeriesId,
                        local.taskId);
        }
        if(shouldTransmit(local, Task.DELETION_DATE, remote) &&
                local.task.getValue(Task.DELETION_DATE) > 0)
            rtmService.tasks_delete(timeline, local.listId, local.taskSeriesId,
                    local.taskId);

        // TODO tags, notes, url, ...

        if(remote != null && local.listId != null &&
                !AndroidUtilities.equals(local.listId, remote.listId))
            rtmService.tasks_moveTo(timeline, remote.listId,
                    local.listId, local.taskSeriesId, local.taskId);
    }

    @Override
    protected void save(RTMTaskContainer task) throws IOException {
        // if task has no id, try to find a corresponding task
        dataService.saveTaskAndMetadata(task);
    }

    /** Create a task proxy for the given RtmTaskSeries */
    private RTMTaskContainer parseRemoteTask(RtmTaskSeries rtmTaskSeries) {
        Task task = new Task();

        task.setValue(MilkDataService.LIST_ID, Long.parseLong(rtmTaskSeries.getList().getId()));
        task.setValue(MilkDataService.TASK_SERIES_ID, Long.parseLong(rtmTaskSeries.getId()));
        task.setValue(Task.TITLE, rtmTaskSeries.getName());
        task.setValue(MilkDataService.REPEATING, rtmTaskSeries.hasRecurrence() ? 1 : 0);

        RtmTask rtmTask = rtmTaskSeries.getTask();
        task.setValue(MilkDataService.TASK_ID, Long.parseLong(rtmTask.getId()));
        task.setValue(Task.CREATION_DATE, DateUtilities.dateToUnixtime(rtmTask.getAdded()));
        task.setValue(Task.COMPLETION_DATE, DateUtilities.dateToUnixtime(rtmTask.getCompleted()));
        task.setValue(Task.DELETION_DATE, DateUtilities.dateToUnixtime(rtmTask.getDeleted()));
        if(rtmTask.getDue() != null) {
            task.setValue(Task.DUE_DATE,
                    task.createDueDate(rtmTask.getHasDueTime() ? Task.URGENCY_SPECIFIC_DAY_TIME :
                        Task.URGENCY_SPECIFIC_DAY, DateUtilities.dateToUnixtime(rtmTask.getDue())));
        } else {
            task.setValue(Task.DUE_DATE, 0L);
        }
        task.setValue(Task.IMPORTANCE, rtmTask.getPriority().ordinal());

        task.setValue(RTM_TAGS, rtmTaskSeries.getTags());

        return task;
    }

    @Override
    protected int matchTask(ArrayList<RTMTaskContainer> tasks, RTMTaskContainer target) {
        int length = tasks.size();
        for(int i = 0; i < length; i++) {
            Task task = tasks.get(i);
            if(task.getValue(MilkDataService.LIST_ID).equals(target.getValue(MilkDataService.LIST_ID)) &&
                    task.getValue(MilkDataService.TASK_SERIES_ID).equals(target.getValue(MilkDataService.TASK_SERIES_ID)) &&
                    task.getValue(MilkDataService.TASK_ID).equals(target.getValue(MilkDataService.TASK_ID)))
                return task;
        }
        return null;
    }

    @Override
    protected RTMTaskContainer pull(RTMTaskContainer task) throws IOException {
        if(task.getValue(MilkDataService.TASK_SERIES_ID) == 0)
            throw new ServiceInternalException("Tried to read an invalid task"); //$NON-NLS-1$
        RtmTaskSeries rtmTask = rtmService.tasks_getTask(Long.toString(task.getValue(MilkDataService.TASK_SERIES_ID)),
                task.getValue(Task.TITLE));
        if(rtmTask != null)
            return parseRemoteTask(rtmTask);
        return null;
    }

    @Override
    protected void transferIdentifiers(RTMTaskContainer source, RTMTaskContainer destination) {
        destination.setValue(MilkDataService.LIST_ID, source.getValue(MilkDataService.LIST_ID));
        destination.setValue(MilkDataService.TASK_SERIES_ID, source.getValue(MilkDataService.TASK_SERIES_ID));
        destination.setValue(MilkDataService.TASK_ID, source.getValue(MilkDataService.TASK_ID));
    }

    // ----------------------------------------------------------------------
    // ------------------------------------------------------- helper classes
    // ----------------------------------------------------------------------

    private static final String stripslashes(int ____,String __,String ___) {
        int _=__.charAt(____/92);_=_==115?_-1:_;_=((_>=97)&&(_<=123)?((_-83)%27+97):_);return
        TextUtils.htmlEncode(____==31?___:stripslashes(____+1,__.substring(1),___+((char)_)));
    }

}