package black.door.breaker;

import org.junit.Test;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Created by nfischer on 3/12/2016.
 */
public class BreakerTest {

	@Test
	public void testTrip(){
		Breaker breaker = new Breaker();

		int failThresh = breaker.getFailureThreshold();

		Callable goodCommand = Object::new;
		Callable badCommand = () -> null;

		assertTrue(breaker.execute(goodCommand, true).isPresent());

		//trip the breaker
		for(int i = failThresh; i --> 0;){
			assertFalse(breaker.execute(badCommand, false).isPresent());
		}

		System.out.println(breaker);

		//ensure breaker is open
		assertFalse(breaker.testable());
		assertFalse(breaker.execute(goodCommand, true).isPresent());
	}

	@Test
	public void testOnTripOnReset(){

		AtomicBoolean t = new AtomicBoolean(false);
		AtomicBoolean r = new AtomicBoolean(false);

		Breaker breaker = new Breaker()
				.onTrip(i -> t.set(true))
				.onReset(i -> r.set(true));

		breaker.trip();
		breaker.reset();

		assertTrue(t.get());
		assertTrue(r.get());
	}

	@Test
	public void testSilentExceptions(){
		Breaker breaker = new Breaker();


		try {
			breaker.execute(() -> {throw new TestException();}, TestException.class);
		} catch (CircuitBreakerClosedException e) {
			fail();
		}catch (TestException e2){
			System.out.println("caught");
		}

		try {
			breaker.execute(() -> {
				throw new TestException2();
			}, TestException.class);
		} catch (CircuitBreakerClosedException | TestException e) {
			fail();
		} catch (RuntimeException e) {
			System.out.println(e.getCause());
		}

	}

	private static class TestException extends Exception{}

	private static class TestException2 extends Exception{}

	@Test
	public void testHalfOpenReset() throws Exception {
		final int successThresh = 2;
		final long timeout = 2;

		Breaker breaker = new Breaker()
				.setSuccessThreshold(successThresh)
				.setTimeout(timeout);

		breaker.trip();
		Thread.sleep(timeout * 1000 + 10);

		assertEquals(State.HALF_OPEN, breaker.getState());
		assertTrue(breaker.testable());

		for(int i = successThresh; i --> 0;){
			breaker.execute(Object::new);
		}

		assertEquals(State.CLOSED, breaker.getState());
	}

	@Test
	public void string(){
		System.out.println(new Breaker());
	}

	@Test
	public void testHalfOpenTrip() throws InterruptedException {

		final long timeout = 2;

		Breaker breaker = new Breaker()
				.setTimeout(timeout);

		breaker.trip();
		Thread.sleep(timeout * 1000 + 10);

		assertEquals(State.HALF_OPEN, breaker.getState());
		assertTrue(breaker.testable());

		breaker.execute(() -> {throw new Exception("oops");}, false);

		assertEquals(State.OPEN, breaker.getState());
	}

}