package black.door.breaker;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by nfischer on 3/13/2016.
 */
public class CircuitFuture<V> implements Future<V> {

	private final Future<V> future;
	private final Breaker breaker;
	private final AtomicBoolean noted = new AtomicBoolean(false);

	public CircuitFuture(Future<V> future, Breaker breaker) {
		this.future = future;
		this.breaker = breaker;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		return future.cancel(mayInterruptIfRunning);
	}

	@Override
	public boolean isCancelled() {
		return future.isCancelled();
	}

	@Override
	public boolean isDone() {
		return future.isDone();
	}

	@Override
	public V get() throws InterruptedException, ExecutionException {
		try {
			return get(Long.MAX_VALUE, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new RuntimeException(e); // that's never happening
		}
	}

	@Override
	public V get(long timeout, TimeUnit unit) throws InterruptedException,
			ExecutionException, TimeoutException {
		try {
			V result = future.get(timeout, unit);
			note(false);
			return result;
		}catch (InterruptedException | ExecutionException e){
			note(true);
			throw e;
		}
	}

	private void note(boolean fail){
		if(!noted.get()) {
			synchronized (noted) {
				if (!noted.get()) {
					if(fail) breaker.fail();
					else breaker.succeed();
					noted.set(true);
				}
			}
		}
	}
}
