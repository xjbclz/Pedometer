package cn.meetdevin.healthylife.Pedometer.Presenter;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import java.util.Calendar;
import java.util.List;

import cn.meetdevin.healthylife.MyApplication;
import cn.meetdevin.healthylife.Pedometer.Dao.StepsDBHandler;
import cn.meetdevin.healthylife.Pedometer.Dao.StepsDataSP;
import cn.meetdevin.healthylife.Pedometer.Model.StepsItemModel;
import cn.meetdevin.healthylife.Pedometer.View.PedometerActivity;
import cn.meetdevin.healthylife.R;
import cn.meetdevin.healthylife.RegisterCallback;
import cn.meetdevin.healthylife.StepsChangeCallback;
import cn.meetdevin.mbarchartopenlib.DataMod;

/**
 * Remote Service
 * 计步器远程服务
 * 使用方法：
 * - 客户端与本服务绑定
 * - 通过 IPC 获取实时步数
 * -------------
 * Remote Service 远程服务，独立于进程
 * 参考：http://www.jianshu.com/p/4a83becd758e
 * 和：http://www.linuxidc.com/Linux/2015-01/111148.htm
 * -------------
 * Created by XinZh on 2017/2/24.
 */

public class PedometerService extends Service implements MyStepDcretor.OnSensorChangeListener {
    private final String TAG = "PedometerService";
    private final int upDateSteps = 0;
    private final int upDateDB = 1;
    private boolean isComplete = false;
    private boolean isBreakRecorder = false;

    private static final int ignore = -1;

    private StepsItemModel stepsItemModel;//本次计步mod
    private List<DataMod> TodayDataList;
    private int formerTotalSteps = 0;//今日往次步数
    private int formerTotalMinutes = 0;//今日往次时间
    private int lastRecorder;//上次的最高记录
    private int goal;
    private long millis;//用于计算一次计步过程的持续时间

    private StepsDBHandler stepsDBHandler;

    private SensorManager sensorManager;
    //自定义的加速度传感器监听
    private MyStepDcretor myStepDcretor;

    /**
     * Android AIDL 接口描述语言，实现IPC 进程间通信
     * 实现接口：给客户端的Stub，Stub继承自Binder，它实现了IBinder接口
     * 两个方法分别用于注册/反注册 StepsChangeCallback 对象给 RemoteCallbackList
     * 参考：http://blog.csdn.net/goodlixueyong/article/details/50299963
     * 和http://www.race604.com/communicate-with-remote-service-3/
     */
    public RemoteCallbackList<StepsChangeCallback> callbackList = new RemoteCallbackList();

    public RegisterCallback.Stub mBinder = new RegisterCallback.Stub() {
        @Override
        public void registerStepsChangeCallback(StepsChangeCallback callback) throws RemoteException {
            callbackList.register(callback);
            //绑定时便更新UI，注意顺序不能反
            upDateUI(upDateDB);
            upDateUI(upDateSteps);
        }
        @Override
        public void unRegisterStepsChangeCallback(StepsChangeCallback callback) throws RemoteException {
            callbackList.unregister(callback);
        }
    };

//    //定义接口：用于服务重启时重设计步监听中的步数
//    public interface controlMyStepDcretor{
//        void reSetSteps(int newSteps);
//    }

    /**
     * @param intent
     * @return
     */
    @Override
    public IBinder onBind(Intent intent) {
        //Stub其实就是IBinder的子类，此客户端可通过此对象控制服务
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG,"onCreate");
        recoveryTemp();

        /**
         * 计步逻辑在这里实现
         * 其实不用开启线程，因为这是Remote Service，与UI不在同一个进程中，不存在阻塞问题
         */
        new Thread(new Runnable() {
            @Override
            public void run() {
//               while (counter< 100){
//                    try {
//                        counter++;//模拟计步
//                        Log.d("Service","steps: "+counter);
//                        Thread.sleep(1000);
//                        int len = callbackList.beginBroadcast();
//                        for (int i =0;i< len;i++){
//                            //回调给StepsActivity
//                            callbackList.getBroadcastItem(i).onStepsChange(counter);
//                        }
//                        callbackList.finishBroadcast();
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    } catch (RemoteException e) {
//                        e.printStackTrace();
//                    }
//                }
                //注册加速度传感器和监听,并且从本地存储初始化步数
                myStepDcretor = new MyStepDcretor(stepsItemModel.getSteps());

                sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
                Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                sensorManager.registerListener(myStepDcretor,sensor,SensorManager.SENSOR_DELAY_UI);

                myStepDcretor.setOnSensorChangeListener(PedometerService.this);
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //初始化数据
        stepsDBHandler = new StepsDBHandler();
        recoveryTemp();

        formerTotalSteps = 0;
        formerTotalMinutes = 0;
        TodayDataList = StepsDataIntegration.getTodayData(ignore,ignore,ignore);
        for (int i=0;i<TodayDataList.size();i++){
            formerTotalSteps += TodayDataList.get(i).getVal();
            formerTotalMinutes += TodayDataList.get(i).getMintues();
        }

        lastRecorder = StepsDataSP.getRecorder();
        goal = StepsDataSP.getGoal();

        //发送定时广播唤醒自己
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        int anHour = 3*60*1000; //3分钟毫秒数
        long triggerAtTime = SystemClock.elapsedRealtime() + anHour;
        Intent intent1 = new Intent(this, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent1, 0);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtTime, pi);

        /*
         * 如果service进程被kill掉，保留service的状态为开始状态，但不保留递送的intent对象。
         * 随后系统会尝试重新创建service，由于服务状态为开始状态，所以创建服务后一定会调用onStartCommand(Intent,int,int)方法。
         * 如果在此期间没有任何启动命令被传递到service，那么参数Intent将为null
         */
        return START_STICKY;//super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //取消所有回调
        callbackList.kill();
        //存储临时数据
        tempSave(stepsItemModel);
        Log.d(TAG,"onDestroy");
        //发送定时广播唤醒自己
//        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
//        int anHour = 60*1000; //一分钟
//        long triggerAtTime = SystemClock.elapsedRealtime() + anHour;
//        Intent intent1 = new Intent(this, AlarmReceiver.class);
//        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent1, 0);
//        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtTime, pi);
    }

    /**
     * 以下两个方法都为：
     *  接口 MyStepDcretor.OnSensorChangeListener 中定义的方法
     *  @onStepsListenerChange 当步数改变时
     *  @onPedometerStateChange 当计步状态改变时
     */
    @Override
    public void onStepsListenerChange(int steps) {
        int d = (int) ((System.currentTimeMillis() - millis)/1000/60);
        stepsItemModel.setMinutes(d);
        stepsItemModel.setSteps(steps);
        tempSave(stepsItemModel);

        //判断是否破记录
        if(isBreakRecorder==false){
            if(steps + formerTotalSteps >= lastRecorder){
                lastRecorder = steps + formerTotalSteps;
                isBreakRecorder = true;
                Calendar calendar = Calendar.getInstance();
                StepsDataSP.setRecorder(steps + formerTotalSteps,
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH)+1,
                        calendar.get(Calendar.DAY_OF_MONTH));
                sendNotify("新的步数记录！","新纪录："+lastRecorder+" 步","继续努力哦");
            }
        }

        //判断是否实现目标
        if(isComplete==false){
            if(steps + formerTotalSteps >= goal){
                sendNotify("今天的步行目标已实现！","做的不错！继续努力","目标已实现！");
            }
            isComplete = true;
        }

        Log.d(TAG,"steps: "+steps + "time: "+ d +"m");
        upDateUI(upDateSteps);
    }

    @Override
    public void onPedometerStateChange(int pedometerState) {
        //Toast.makeText(MyApplication.getContext()," "+pedometerState,Toast.LENGTH_SHORT).show();
        Log.d(TAG,"onPedometerStateChange:" + pedometerState);
        if(pedometerState == 2){
            //开始计步
            millis = System.currentTimeMillis();
            Calendar calendar = Calendar.getInstance();
            stepsItemModel.setYear(calendar.get(Calendar.YEAR));
            stepsItemModel.setMonth(calendar.get(Calendar.MONTH)+1);
            stepsItemModel.setDay(calendar.get(Calendar.DAY_OF_MONTH));
            stepsItemModel.setStartHour(calendar.get(Calendar.HOUR_OF_DAY));
        }
        if(pedometerState == 0){
            //计步结束存储一次计步数据
            stepsDBHandler.insertStepsData(stepsItemModel);

            TodayDataList = StepsDataIntegration.getTodayData(ignore,ignore,ignore);
            formerTotalSteps = 0;
            formerTotalMinutes = 0;
            for (int i=0;i<TodayDataList.size();i++){
                formerTotalSteps += TodayDataList.get(i).getVal();
                formerTotalMinutes += TodayDataList.get(i).getMintues();
            }
            Log.d(TAG,"onPedometerStateChange: 记录了一次持续"+stepsItemModel.getMinutes()+"分钟的运动");
            StepsDataSP.cleanTempSteps();
            stepsItemModel.clean();
            //通知UI更新今日数据
            upDateUI(upDateDB);
            upDateUI(upDateSteps);
            //Toast.makeText(MyApplication.getContext(),"记录了一次持续"+d+"分钟的运动",Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 存储本次计步过程
     */
    private void tempSave(StepsItemModel stepsItemModel){
        StepsDataSP.tempSaveSteps(stepsItemModel);
    }

    /**
     * 恢复本次计步过程
     */
    private void recoveryTemp(){
        stepsItemModel = StepsDataSP.tempRecoverySteps();
        upDateUI(upDateSteps);
    }

    /**
     *  更新 UI
     *  回调StepsChangeCallback.aidl 接口，向客户端(UI)传递数据
     */
    private void upDateUI(int flag){
        int len = callbackList.beginBroadcast();
        //回调给所有绑定的客户端
        for (int i =0;i< len;i++){
            try {
                switch (flag){
                    case upDateSteps:
                        callbackList.getBroadcastItem(i).onStepsChange(stepsItemModel.getSteps(),
                                stepsItemModel.getSteps() + formerTotalSteps,
                                stepsItemModel.getMinutes() + formerTotalMinutes,
                                lastRecorder);
                        break;
                    case upDateDB:
                        callbackList.getBroadcastItem(i).onFinishStepsItem();
                        break;
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        callbackList.finishBroadcast();
    }

    private void sendNotify(String contentTitle, String contentText,String ticker){
        NotificationManager manager = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(MyApplication.getContext());
        builder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle(contentTitle);//设置通知栏标题
        builder.setContentText(contentText);
        builder.setTicker(ticker);
        builder.setWhen(System.currentTimeMillis());//通知产生的时间，系统获取到的时间
        builder.setDefaults(Notification.DEFAULT_VIBRATE);//向通知添加声音、闪灯和振动效果的最简单、最一致的方式是使用当前的用户默认设置，使用defaults属性，可以组合
        builder.setPriority(Notification.PRIORITY_DEFAULT); //设置该通知优先级
        builder.setOngoing(false);//ture，设置他为一个正在进行的通知。他们通常是用来表示一个后台任务,用户积极参与(如播放音乐)或以某种方式正在等待,因此占用设备(如一个文件下载,同步操作,主动网络连接)
        Intent intent = new Intent(this,PedometerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        builder.setContentIntent(pendingIntent);
        manager.notify(1,builder.build());
    }

    public static void stopService(){
        stopService();
    }
}
