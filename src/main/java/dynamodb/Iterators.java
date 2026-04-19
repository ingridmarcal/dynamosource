package dynamodb;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * Small iterator helpers used by the connector. Kept package-private and
 * dependency-free so the connector does not need Guava at runtime.
 */
final class Iterators {

    private Iterators() {
    }

    /**
     * Flatten an iterator of paginated responses into a single lazy iterator of
     * items. Pages are pulled one at a time; the inner list is fully consumed
     * before advancing to the next page.
     */
    static <P, T> Iterator<T> flatten(Iterator<P> pages, Function<P, List<T>> extractor) {
        return new Iterator<T>() {
            private Iterator<T> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && pages.hasNext()) {
                    List<T> items = extractor.apply(pages.next());
                    current = items == null ? Collections.emptyIterator() : items.iterator();
                }
                return current.hasNext();
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return current.next();
            }
        };
    }
}
