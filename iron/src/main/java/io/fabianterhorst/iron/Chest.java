package io.fabianterhorst.iron;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.schedulers.Schedulers;
import rx.subscriptions.Subscriptions;

public class Chest {

    private final Storage mStorage;

    private final transient ArrayList<DataChangeCallback> mCallbacks = new ArrayList<>();

    private final Loader mLoader;

    public interface Transaction<T> {
        void execute(T value);
    }

    public interface ReadCallback<T> {
        void onResult(T value);
    }

    protected Chest(Context context, String dbName, Loader loader, Encryption encryption, int cache) {
        mStorage = new DbStoragePlainFile(context.getApplicationContext(), dbName, encryption, cache);
        mLoader = loader;
    }

    /**
     * Destroys all data saved in Chest.
     */
    public void destroy() {
        mStorage.destroy();
    }

    /**
     * Saves any types of POJOs or collections in Chest storage.
     *
     * @param key   object key is used as part of object's file name
     * @param value object to save, must have no-arg constructor, can't be null.
     * @param <T>   object type
     * @return this Chest instance
     */
    public <T> Chest write(String key, T value) {
        synchronized (mStorage) {
            if (value == null) {
                throw new IronException("Iron doesn't support writing null root values");
            } else {
                mStorage.insert(key, value);
                callCallbacks(key, value);
            }
        }
        return this;
    }

    public <T> Chest write(Class clazz, T value) {
        write(clazz.getName(), value);
        return this;
    }

    /**
     * Saves any types of POJOs or collections in Chest storage async
     *
     * @param key   object key is used as part of object's file name
     * @param value object to save, must have no-arg constructor, can't be null.
     * @param <T>   object type
     * @return this Chest instance
     */
    public <T> Chest put(String key, T value) {
        AsyncTask<Object, Void, Void> task = new AsyncTask<Object, Void, Void>() {

            @SuppressWarnings("unchecked")
            @Override
            protected Void doInBackground(Object... objects) {
                String key = (String) objects[0];
                T value = (T) objects[1];
                write(key, value);
                return null;
            }
        };
        if (Build.VERSION.SDK_INT > 10) {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key, value);
        } else {
            task.execute(key, value);
        }
        return this;
    }

    /**
     * Instantiates saved object using original object class (e.g. LinkedList). Support limited
     * backward and forward compatibility: removed fields are ignored, new fields have their
     * default values.
     * <p/>
     * All instantiated objects must have no-arg constructors.
     *
     * @param key          object key to read
     * @param readCallback callback that return the readed object
     */
    public <T> void get(String key, ReadCallback<T> readCallback) {
        get(key, readCallback, null);
    }

    /**
     * Instantiates saved object using original object class (e.g. LinkedList). Support limited
     * backward and forward compatibility: removed fields are ignored, new fields have their
     * default values.
     * <p/>
     * All instantiated objects must have no-arg constructors.
     *
     * @param key           object key to read
     * @param readCallback  callback that return the readed object
     * @param defaultObject return the defaultObject if readed object is null
     */
    public <T> void get(String key, ReadCallback<T> readCallback, Object defaultObject) {
        AsyncTask<Object, Void, T> task = new AsyncTask<Object, Void, T>() {

            protected ReadCallback<T> mReadCallback;

            @Override
            protected T doInBackground(Object... objects) {
                String key = (String) objects[0];
                mReadCallback = (ReadCallback<T>) objects[1];
                T defaultObject = null;
                if (objects.length > 2)
                    defaultObject = (T) objects[2];
                return read(key, defaultObject);
            }

            @Override
            protected void onPostExecute(T value) {
                mReadCallback.onResult(value);
            }
        };
        if (Build.VERSION.SDK_INT > 10) {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key, readCallback, defaultObject);
        } else {
            task.execute(key, readCallback, defaultObject);
        }
    }

    public <T> Observable<T> get(final String key) {
        return Observable.create(new Observable.OnSubscribe<T>() {
            @Override
            public void call(final Subscriber<? super T> subscriber) {
                final DataChangeCallback<T> callback = new DataChangeCallback<T>(key) {
                    @Override
                    public void onDataChange(T value) {
                        if (!subscriber.isUnsubscribed()) {
                            subscriber.onNext(value);
                        }
                    }
                };
                Chest.this.addOnDataChangeListener(callback);
                subscriber.add(Subscriptions.create(new Action0() {
                    @Override
                    public void call() {
                        Chest.this.removeListener(callback);
                    }
                }));
                subscriber.onNext(Chest.this.<T>read(key));
            }
        }).compose(this.<T>applySchedulers());
    }

    public <T> Observable<Boolean> set(final String key, final T object) {
        return Observable.fromCallable(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                Iron.chest().write(key, object);
                return true;
            }
        }).compose(this.<Boolean>applySchedulers());
    }

    public Observable<Boolean> remove(final String key) {
        return Observable.fromCallable(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                Iron.chest().delete(key);
                return true;
            }
        }).compose(this.<Boolean>applySchedulers());
    }

    public Observable<Boolean> removeAll() {
        return Observable.fromCallable(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                Iron.chest().deleteAll();
                return true;
            }
        }).compose(this.<Boolean>applySchedulers());
    }

    /**
     * Execute a subscriber in this you can modify the data from the parameter with a data saving after modifying is finished
     *
     * @param key data key
     * @return the observable
     */
    public <T> Observable<T> execute(final String key) {
        return Observable.create(new Observable.OnSubscribe<T>() {
            @Override
            public void call(Subscriber<? super T> subscriber) {
                T value = read(key);
                subscriber.onNext(value);
                write(key, value);
                subscriber.onCompleted();
            }
        });
    }

    /**
     * Apply the default android schedulers to a observable
     *
     * @param <T> the current observable
     * @return the transformed observable
     */
    protected <T> Observable.Transformer<T, T> applySchedulers() {
        return new Observable.Transformer<T, T>() {
            @Override
            public Observable<T> call(Observable<T> tObservable) {
                tObservable.subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .unsubscribeOn(Schedulers.io());
                return tObservable;
            }
        };
    }

    /**
     * Execute a transaction in this you can modify the data from the parameter with a data saving after modifying is finished
     *
     * @param key           data key
     * @param transaction   transaction
     * @param defaultObject default object if value is null
     */
    public <T> void execute(String key, Transaction<T> transaction, Object defaultObject) {
        AsyncTask<Object, Void, Void> task = new AsyncTask<Object, Void, Void>() {

            @Override
            protected Void doInBackground(Object... objects) {
                String key = (String) objects[0];
                Transaction<T> transaction = (Transaction<T>) objects[1];
                T defaultObject = (T) objects[2];
                T value = read(key);
                if (value == null)
                    value = defaultObject;
                transaction.execute(value);
                if (value != null)
                    write(key, value);
                return null;
            }
        };
        if (Build.VERSION.SDK_INT > 10) {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key, transaction, defaultObject);
        } else {
            task.execute(key, transaction, defaultObject);
        }
    }

    public void removeAsync(String key) {
        AsyncTask<Object, Void, Void> task = new AsyncTask<Object, Void, Void>() {
            @Override
            protected Void doInBackground(Object... objects) {
                String key = (String) objects[0];
                delete(key);
                return null;
            }
        };
        if (Build.VERSION.SDK_INT > 10) {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, key);
        } else {
            task.execute(key);
        }
    }

    public void removeAllAsync() {
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                deleteAll();
                return null;
            }
        };
        if (Build.VERSION.SDK_INT > 10) {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            task.execute();
        }
    }

    public void execute(String key, Transaction transaction) {
        execute(key, transaction, null);
    }

    /**
     * Instantiates saved object using original object class (e.g. LinkedList). Support limited
     * backward and forward compatibility: removed fields are ignored, new fields have their
     * default values.
     * <p/>
     * All instantiated objects must have no-arg constructors.
     *
     * @param key object key to read
     * @return the saved object instance or null
     */
    public <T> T read(String key) {
        return read(key, null);
    }

    public <T> T read(Class clazz) {
        return read(clazz.getName(), null);
    }

    public <T> T read(Class clazz, T defaultValue) {
        return read(clazz.getName(), defaultValue);
    }

    /**
     * Instantiates saved object using original object class (e.g. LinkedList). Support limited
     * backward and forward compatibility: removed fields are ignored, new fields have their
     * default values.
     * <p/>
     * All instantiated objects must have no-arg constructors.
     *
     * @param key          object key to read
     * @param defaultValue will be returned if key doesn't exist
     * @return the saved object instance or null
     */
    public <T> T read(String key, T defaultValue) {
        T value = mStorage.select(key);
        return value == null ? defaultValue : value;
    }


    /**
     * Check if an object with the given key is saved in Chest storage.
     *
     * @param key object key
     * @return true if object with given key exists in Chest storage, false otherwise
     */
    public boolean exist(String key) {
        return mStorage.exist(key);
    }

    /**
     * Delete saved object for given key if it is exist.
     *
     * @param key object key
     */
    public void delete(String key) {
        mStorage.deleteIfExists(key);
    }

    public void deleteAll() {
        for (String key : getAllKeys())
            delete(key);
    }

    /**
     * Returns all keys for objects in chest.
     *
     * @return all keys
     */
    public List<String> getAllKeys() {
        return mStorage.getAllKeys();
    }

    /**
     * add data change callback to callbacks
     *
     * @param callback data change callback
     */
    public void addOnDataChangeListener(DataChangeCallback callback) {
        mCallbacks.add(callback);
    }

    /**
     * remove all listener from this object
     *
     * @param object Object with listeners
     */
    synchronized public void removeListener(Object object) {
        Iterator<DataChangeCallback> i = mCallbacks.iterator();
        while (i.hasNext()) {
            DataChangeCallback callback = i.next();
            if (callback.getClassName() != null && callback.getClassName().equals(object.getClass().getName()))
                i.remove();
        }
    }

    synchronized public void removeListener(DataChangeCallback callback) {
        Iterator<DataChangeCallback> i = mCallbacks.iterator();
        while (i.hasNext()) {
            DataChangeCallback currentCallback = i.next();
            if (currentCallback.getIdentifier() != null && currentCallback.getIdentifier().equals(callback.getIdentifier()))
                i.remove();
        }

    }

    /**
     * call all data change callbacks
     */
    @SuppressWarnings("unchecked")
    public <T> void callCallbacks(String key, T value) {
        if (mCallbacks != null) {
            synchronized (mCallbacks) {
                for (DataChangeCallback callback : mCallbacks) {
                    if (callback.getType() != null && callback.getType().isInstance(value)) {
                        Class clazz = null;
                        if (callback.getType().equals(List.class)) {
                            List<T> values = (List) value;
                            if (values.size() > 0)
                                clazz = values.get(0).getClass();
                        }
                        if (callback.getKey() != null) {
                            if (callback.getKey().equals(key)) {
                                callback.onDataChange(value);
                                callback.onDataChange(key, value);
                            }
                        } else if (callback.getValues() != null) {
                            for (Enum enumValue : callback.getValues()) {
                                if (enumValue.toString().equals(key)) {
                                    callback.onDataChange(key, value);
                                    callback.onDataChange(value);
                                }
                            }
                        } else {
                            callback.onDataChange(key, value);
                            callback.onDataChange(value);
                            if (clazz != null)
                                callback.onDataChange(clazz, value);
                        }
                    } else if (callback.getType() == null) {
                        if (callback.getKey() != null) {
                            if (callback.getKey().equals(key)) {
                                callback.onDataChange(value);
                                callback.onDataChange(key, value);
                            }
                        } else {
                            callback.onDataChange(key, value);
                            callback.onDataChange(value);
                        }
                    }
                }
            }
        }
    }

    /**
     * load objects from loader
     *
     * @param call extension call
     * @param key  key to save
     */
    public <T> void load(T call, String key) {
        if (mLoader == null)
            throw new IronException("To use load() you have to set the loader in your application onCreate() with Iron.setLoader(new IronRetrofit())");
        mLoader.load(call, key);
    }

    /**
     * load objects from loader
     *
     * @param call  call
     * @param clazz classname to save
     */
    public <T> void load(T call, Class clazz) {
        load(call, clazz.getName());
    }

    /**
     * load objects from observable into storage
     *
     * @param observable observable to subscribe
     * @param key        key to save
     */
    public <T> void load(Observable<T> observable, final String key) {
        observable.subscribe(new Subscriber<T>() {
            @Override
            public void onCompleted() {
                if(!isUnsubscribed()) {
                    unsubscribe();
                }
            }

            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
            }

            @Override
            public void onNext(T o) {
                if(!isUnsubscribed()) {
                    Chest.this.write(key, o);
                }
            }
        });
    }

    /**
     * load objects from observable into storage
     *
     * @param observable observable to subscribe
     * @param clazz      classname to save
     */
    public <T> void load(Observable<T> observable, Class clazz) {
        load(observable, clazz.getName());
    }

    /**
     * Clears cache for given key.
     *
     * @param key object key
     */
    public void invalidateCache(String key) {
        mStorage.invalidateCache(key);
    }

    /**
     * Clears cache.
     */
    public void invalidateCache() {
        mStorage.invalidateCache();
    }
}
