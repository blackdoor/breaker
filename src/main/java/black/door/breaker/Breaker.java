package black.door.breaker;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.*;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static black.door.breaker.State.*;

/**
 * Created by nfischer on 3/12/2016.
 */
@EqualsAndHashCode
@Getter
@Setter(AccessLevel.PROTECTED)
@ToString
public class Breaker {
	public static final int DEFAULT_FAILURE_THRESHOLD;
	public static final int DEFAULT_SUCCESS_THRESHOLD;
	/**
	 * in seconds
	 */
	public static final long DEFAULT_TIMEOUT;
	private static final ScheduledExecutorService TIMEOUT_EXECUTOR;

	static {
		int corePool = Runtime.getRuntime().availableProcessors() / 2;
		corePool = corePool == 0 ? 1 : corePool;
		TIMEOUT_EXECUTOR = Executors.newScheduledThreadPool(corePool);

		Config conf = ConfigFactory.load();
		DEFAULT_FAILURE_THRESHOLD = conf.getInt("black.door.breaker.defaultFailureThresh");
		DEFAULT_SUCCESS_THRESHOLD = conf.getInt("black.door.breaker.defaultSuccessThresh");
		DEFAULT_TIMEOUT = conf.getLong("black.door.breaker.defaultTimeout");
	}

	//region properties

	/**
	 * The number of failures required before the breaker trips.
	 */
	private int failureThreshold;

	/**
	 * The number of successful executions required for a half-open breaker to
	 * reset.
	 */
	private int successThreshold;

	/**
	 * The amount of time in seconds a closed breaker will wait before allowing
	 * execution to be re-attempted.
	 */
	private long timeout;

	/**
	 * if true, any successful execution on a closed breaker will reset the
	 * failure counter.
	 */
	private boolean resetOnSuccess;

	@Getter(AccessLevel.NONE)
	private final AtomicInteger failures;

	@Getter(AccessLevel.NONE)
	private final AtomicInteger successes;

	@Getter(AccessLevel.NONE)
	private ScheduledFuture timeoutEvent;

	/**
	 * The current state of the breaker.
	 */
	private State state;

	/**
	 * The action to take when the breaker is tripped. The Instant is the time
	 * at which the trip happened.
	 *
	 * Useful for logging or alert generation.
	 */
	@Getter(AccessLevel.NONE)
	private Consumer<Instant> onTrip;

	/**
	 * The action to take when the breaker is reset. The Instant is the time
	 * at which the trip happened.
	 *
	 * Useful for logging or alert generation.
	 */
	@Getter(AccessLevel.NONE)
	private Consumer<Instant> onReset;

	@Getter(AccessLevel.NONE)
	private final Object stateLock = new Object();

	//endregion

	//region constructors

	/**
	 * Create a new breaker using defaults. Default values can be changed in
	 * an application.conf file on the classpath.
	 */
	public Breaker(){
		this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_SUCCESS_THRESHOLD,
				DEFAULT_TIMEOUT);
	}

	/**
	 * Create a new breaker
	 * @param failureThreshold
	 * @param successThreshold
	 * @param timeout
	 */
	public Breaker(int failureThreshold, int successThreshold, long timeout)
	{
		this
				.setFailureThreshold(failureThreshold)
				.setSuccessThreshold(successThreshold)
				.setTimeout(timeout);

		failures = new AtomicInteger();
		successes = new AtomicInteger();
		state = CLOSED;

		onTimeout = buildOnTimeout();
	}

	//endregion

	private Runnable buildOnTimeout(){
		return () -> {
			synchronized (stateLock) {
				if (state == OPEN) {
					successes.set(0);
					state = HALF_OPEN;
				}
			}
		};
	}
	private final Runnable onTimeout;

	//region incremental state suggesting methods

	/**
	 * Indicate that an execution has failed.
	 */
	public void fail(){
		switch (state){
			case CLOSED:
				if(failures.incrementAndGet() >= failureThreshold){
					trip();
				}
				break;
			case HALF_OPEN:
				trip();
				break;
		}
	}

	protected void succeed(){
		switch (state){
			case CLOSED:
				if(successes.incrementAndGet() >= successThreshold)
					failures.set(0);
				break;
			case HALF_OPEN:
				if(successes.incrementAndGet() >= successThreshold){
					reset();
				}
				break;
		}
	}

	//endregion

	//region instant state changing methods

	/**
	 * Hard reset the breaker back to the closed state.
	 */
	public void reset(){
		synchronized (stateLock){
			if(state != CLOSED){
				state = CLOSED;
				failures.set(0);
				successes.set(0);
				timeoutEvent.cancel(false);
				if(onReset != null){
					onReset.accept(Instant.now());
				}
			}
		}
	}

	/**
	 * Hard trip the breaker into the open state.
	 */
	public void trip(){
		synchronized (stateLock) {
			if (state != OPEN) {
				state = OPEN;
				timeoutEvent = TIMEOUT_EXECUTOR.schedule(onTimeout, timeout,
						TimeUnit.SECONDS);
				if(onTrip != null) {
					onTrip.accept(Instant.now());
				}
			}
		}
	}

	//endregion

	protected boolean testable(){
		switch (state){
			case OPEN:
				return false;
			case CLOSED:
				return true;
			case HALF_OPEN:
				return true;
			default:
				return true;
		}
	}

	/**
	 * Execute an operation on this breaker. Any exceptions thrown by op will
	 * indicate this execution has failed. You can also explicitly indicate failure
	 * with Breaker#fail() if you do not want to throw an exception.
	 * @param op
	 * @param <V>
	 * @return
	 * @throws CircuitBreakerClosedException if the breaker is currently closed
	 * @throws Exception any exception thrown by op
	 */
	public <V>  V execute(Callable<V> op) throws CircuitBreakerClosedException,
			Exception {
		if(!testable()) {
			throw new CircuitBreakerClosedException();
		}

		try {
			V ret = op.call();
			succeed();
			return ret;
		}catch (Exception e){
			fail();
			throw e;
		}
	}

	/**
	 * Execute an operation on this breaker. Any exceptions thrown by op will
	 * indicate this execution has failed. You can also explicitly indicate failure
	 * with Breaker#fail() if you do not want to throw an exception.
	 * You can also pass a false value for nullable and then return null from op
	 * to indicate failure.
	 *
	 * @param op
	 * @param nullable if false, a null value returned by op will be considered a failure
	 * @param <V>
	 * @return
	 */
	public <V> Optional<V> execute(Callable<V> op, boolean nullable) {

		if(!testable()) {
			return Optional.empty();
		}

		try {
			V result = op.call();
			try {
				Optional<V> ret;
				if (nullable) {
					ret = Optional.ofNullable(result);
				} else {
					ret = Optional.of(result);
				}
				succeed();
				return ret;
			} catch (NullPointerException e) {
				fail();
				return Optional.empty();
			}
		} catch (Exception e) {
			fail();
			return Optional.empty();
		}
	}

	/**
	 * The same behavior as Breaker#execute(Callable) but any exceptions except
	 * those which are instances of exception will be sneakily re-thrown as
	 * RuntimeExceptions.
	 *
	 * @param op
	 * @param exception
	 * @param <V>
	 * @param <E>
	 * @return
	 * @throws CircuitBreakerClosedException
	 * @throws E
	 */
	public <V, E extends Exception> V execute(Callable<V> op,
	                                          Class<? extends E> exception)
			throws CircuitBreakerClosedException, E {

		try {
			return execute(op);
		} catch (Exception e) {

			if (exception.isInstance(e)) {
				throw exception.cast(e);
			}

			throw new RuntimeException(e);
		}
	}

	public <V> Future<V> executeAsync(Supplier<? extends Future<V>> op)
			throws CircuitBreakerClosedException{
		if(!testable())
			throw new CircuitBreakerClosedException();

		Future<V> f = op.get();
		return new CircuitFuture<>(f, this);
	}

	//region configuration

	public Breaker setFailureThreshold(int failureThreshold) {
		this.failureThreshold = failureThreshold;
		return this;
	}

	public Breaker setSuccessThreshold(int successThreshold) {
		this.successThreshold = successThreshold;
		return this;
	}

	public Breaker setTimeout(long timeout) {
		this.timeout = timeout;
		return this;
	}

	public Breaker onTrip(Consumer<Instant> onTrip) {
		this.onTrip = onTrip;
		return this;
	}

	public Breaker onReset(Consumer<Instant> onReset) {
		this.onReset = onReset;
		return this;
	}

	//endregion

}
