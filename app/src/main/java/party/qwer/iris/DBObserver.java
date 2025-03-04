package party.qwer.iris;

public class DBObserver {
    private final KakaoDB kakaoDb;
    private final ObserverHelper observerHelper;
    private Thread pollingThread;
    private volatile boolean isObservingDatabase = false;

    public DBObserver(KakaoDB kakaoDb, ObserverHelper observerHelper) {
        this.kakaoDb = kakaoDb;
        this.observerHelper = observerHelper;
    }

    public void startPolling() {
        if (pollingThread == null || !pollingThread.isAlive()) {
            pollingThread = new Thread(() -> {
                isObservingDatabase = true;
                while (true) {
                    observerHelper.checkChange(kakaoDb);
                    try {
                        long pollingInterval = Configurable.getInstance().getDbPollingRate();
                        if (pollingInterval > 0) {
                            Thread.sleep(pollingInterval);
                        } else {
                            Thread.sleep(1000); // prevent too fast loop if rate is set to 0 or negative.
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Polling thread interrupted: " + e);
                        isObservingDatabase = false;
                        break;
                    }
                }
                isObservingDatabase = false;
            });
            pollingThread.setName("DB-Polling-Thread");
            pollingThread.start();
            System.out.println("DB Polling thread started.");
        } else {
            System.out.println("DB Polling thread is already running.");
        }
    }

    public void stopPolling() {
        if (pollingThread != null && pollingThread.isAlive()) {
            pollingThread.interrupt();
            pollingThread = null;
            isObservingDatabase = false;
            System.out.println("DB Polling thread stopped.");
        }
    }

    public boolean isPollingThreadAlive() {
        return pollingThread != null && pollingThread.isAlive();
    }

    public boolean isObserving() {
        return isObservingDatabase;
    }
}