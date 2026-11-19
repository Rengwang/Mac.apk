import android.app.Activity;
import android.app.ActivityManager;
import java.util.List;

/** ActivityManager must describe ActivityHost's real task, not a fixed empty one. */
public class ActivityManagerTasksTest {
    public static final class RootActivity extends Activity {}
    public static final class ChildActivity extends Activity {}

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        ActivityManager manager = new ActivityManager();
        check(manager.getAppTasks().isEmpty(), "an empty process invented an app task");

        RootActivity root = new RootActivity();
        ChildActivity child = new ChildActivity();
        com.macapk.loader.ActivityHost.push(root);
        com.macapk.loader.ActivityHost.push(child);

        List<ActivityManager.AppTask> handles = manager.getAppTasks();
        check(handles.size() == 1, "single-task host did not expose one AppTask");
        ActivityManager.RecentTaskInfo info = handles.get(0).getTaskInfo();
        check(info.taskId == 1 && info.persistentId == 1, "task identity is not stable");
        check(info.baseActivity.getClassName().equals(RootActivity.class.getName()),
              "baseActivity is not the bottom Activity");
        check(info.topActivity.getClassName().equals(ChildActivity.class.getName()),
              "topActivity is not the current Activity");
        check(info.numActivities == 2 && info.isRunning,
              "task count/run state does not describe the live stack");

        info.baseActivity = null;
        check(handles.get(0).getTaskInfo().baseActivity != null,
              "caller mutation escaped the task-info snapshot");

        List<ActivityManager.RunningTaskInfo> running = manager.getRunningTasks(1);
        check(running.size() == 1, "getRunningTasks(1) did not expose live task");
        check(running.get(0).baseActivity.getClassName().equals(RootActivity.class.getName()),
              "RunningTaskInfo.baseActivity mismatch");
        check(running.get(0).topActivity.getClassName().equals(ChildActivity.class.getName()),
              "RunningTaskInfo.topActivity mismatch");
        check(running.get(0).numActivities == 2 && running.get(0).numRunning == 2 && running.get(0).isRunning,
              "RunningTaskInfo counts/state mismatch");
        check(manager.getRunningTasks(0).isEmpty(), "getRunningTasks(0) must respect maxNum=0");

        List<ActivityManager.RecentTaskInfo> recent = manager.getRecentTasks(1, 0);
        check(recent.size() == 1 && recent.get(0).numActivities == 2
              && recent.get(0).topActivity.getClassName().equals(ChildActivity.class.getName()),
              "getRecentTasks(1, 0) mismatch");
        check(manager.getRecentTasks(0, 0).isEmpty(), "getRecentTasks(0, 0) must respect maxNum=0");

        System.out.println("PASS: AppTask/RunningTasks/RecentTasks expose coherent snapshots");
    }
}
