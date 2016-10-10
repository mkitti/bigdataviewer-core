package bdv.cache;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

import bdv.img.cache.VolatileGlobalCellCache;

public class SoftRefVolatileCacheImp implements VolatileCache
{
	// TODO: this should be a singleton, but for now we create new instances, because bdv cache keys don't identify the viewer (yet).
	public static VolatileCache getInstance()
	{
		return new SoftRefVolatileCacheImp();
	}

	@Override
	public < K, V extends VolatileCacheValue >
		VolatileCacheEntry< K, V > put( final K key, final V value, final VolatileCacheValueLoader< K, V > loader )
	{
		final Entry< K, V > entry = new Entry<>( key, value, loader );
		if ( value.isValid() )
			softReferenceCache.put( key, new MySoftReference<>( entry, finalizeQueue ) );
		else
			softReferenceCache.put( key, new MyWeakReference<>( entry, finalizeQueue ) );
		return entry;
	}

	@SuppressWarnings( "unchecked" )
	@Override
	public < K, V extends VolatileCacheValue >
		VolatileCacheEntry< K, V > get( final K key )
	{
		final Reference< Entry< ?, ? > > ref = softReferenceCache.get( key );
		return ref == null ? null : ( VolatileCacheEntry< K, V > ) ref.get();
	}

	@Override
	public void clearCache()
	{
		for ( final Reference< Entry< ?, ? > > ref : softReferenceCache.values() )
			ref.clear();
		softReferenceCache.clear();
	}


	@Override
	public void finalizeRemovedCacheEntries()
	{
		synchronized ( softReferenceCache )
		{
			for ( int i = 0; i < MAX_PER_FRAME_FINALIZE_ENTRIES; ++i )
			{
				final Reference< ? extends Entry< ?, ? > > poll = finalizeQueue.poll();
				if ( poll == null )
					break;
				final Object key = ( ( GetKey< ? > ) poll ).getKey();
				final Reference< Entry< ?, ? > > ref = softReferenceCache.get( key );
				if ( ref == poll )
					softReferenceCache.remove( key );
			}
		}
	}

	class Entry< K, V extends VolatileCacheValue > implements VolatileCacheEntry< K, V >
	{
		private final K key;

		private V value;

		private final VolatileCacheValueLoader< K, V > loader;

		/**
		 * When was this entry last enqueued for loading (see
		 * {@link VolatileGlobalCellCache#currentQueueFrame}). This is initialized
		 * to -1. When the entry's data becomes valid, it is set to
		 * {@link Long#MAX_VALUE}.
		 */
		private long enqueueFrame;

		public Entry( final K key, final V data, final VolatileCacheValueLoader< K, V > loader )
		{
			this.key = key;
			this.value = data;
			this.loader = loader;
			enqueueFrame = -1;
		}

		@Override
		public void loadIfNotValid() throws InterruptedException
		{
			// TODO: assumption for following synchronisation pattern is that isValid() will never go from true to false. When invalidation API is added, that might change.
			if ( !value.isValid() )
			{
				synchronized ( this )
				{
					if ( !value.isValid() )
					{
						value = loader.load( key );
						enqueueFrame = Long.MAX_VALUE;
						softReferenceCache.put( key, new MySoftReference<>( this, finalizeQueue ) );
						notifyAll();
					}
				}
			}
		}

		@Override
		public K getKey()
		{
			return key;
		}

		@Override
		public V getValue()
		{
			return value;
		}

		@Override
		public long getEnqueueFrame()
		{
			return enqueueFrame;
		}

		@Override
		public void setEnqueueFrame( final long f )
		{
			enqueueFrame = f;
		}
	}

	private static interface GetKey< K >
	{
		public K getKey();
	}

	private static class MySoftReference< K > extends SoftReference< Entry< ?, ? > > implements GetKey< K >
	{
		private final K key;

		public MySoftReference( final Entry< K, ? > referent, final ReferenceQueue< ? super Entry< ?, ? > > q )
		{
			super( referent, q );
			key = referent.key;
		}

		@Override
		public K getKey()
		{
			return key;
		}
	}

	private static class MyWeakReference< K > extends WeakReference< Entry< ?, ? > > implements GetKey< K >
	{
		private final K key;

		public MyWeakReference( final Entry< K, ? > referent, final ReferenceQueue< ? super Entry< ?, ? > > q )
		{
			super( referent, q );
			key = referent.key;
		}

		@Override
		public K getKey()
		{
			return key;
		}
	}

	private static final int MAX_PER_FRAME_FINALIZE_ENTRIES = 500;

	private final ConcurrentHashMap< Object, Reference< Entry< ?, ? > > > softReferenceCache = new ConcurrentHashMap<>();

	private final ReferenceQueue< Entry< ?, ? > > finalizeQueue = new ReferenceQueue<>();

	private SoftRefVolatileCacheImp()
	{}

//	private static SoftRefVolatileCacheImp instance = new SoftRefVolatileCacheImp();
}