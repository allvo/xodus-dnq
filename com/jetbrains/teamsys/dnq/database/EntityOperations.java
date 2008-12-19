package com.jetbrains.teamsys.dnq.database;

import com.jetbrains.teamsys.database.*;
import com.jetbrains.teamsys.dnq.association.AssociationSemantics;
import jetbrains.mps.internal.collections.runtime.ISelector;
import jetbrains.mps.internal.collections.runtime.ListSequence;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

// TODO: move this class to the associations semantics package
public class EntityOperations {

  private static final Log log = LogFactory.getLog(EntityOperations.class);

  private EntityOperations() {
  }

  public static void remove(Entity e) {
    if (e == null || ((TransientEntity) e).isRemoved()) {
      return;
    }

    e = TransientStoreUtil.reattach((TransientEntity) e);
    TransientEntityStore store = (TransientEntityStore) e.getStore();

    ModelMetaData md = store.getModelMetaData();
    if (md != null) {
      // cascade delete
      EntityMetaData emd = md.getEntityMetaData(e.getType());
      if (emd != null) {
        // call destructors starting with it and continuing with super class destructor up to root super class
        executeDestructors(e, md);

        // remove associations and cascade delete 
        ConstraintsUtil.processOnDeleteConstraints(store.getThreadSession(), e, emd, md);
      }
    }

    // delete itself
    e.delete();
  }

  private static void executeDestructors(@NotNull Entity e, @NotNull ModelMetaData md) {
    EntityMetaData emd = md.getEntityMetaData(e.getType());
    emd.callDestructor(e);
  }

  public static List<Entity> getHistory(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e == null ? Collections.EMPTY_LIST : e.getHistory();
  }

  public static boolean isRemoved(@NotNull Entity e) {
    return e == null || ((TransientEntity)e).isRemoved() || TransientStoreUtil.reattach((TransientEntity) e) == null;
  }

  public static int getVersion(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e == null ? -1 : e.getVersion();
  }

  public static Entity getPreviousVersion(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e == null ? null : e.getPreviousVersion();
  }

  public static Entity getNextVersion(@NotNull Entity e) {
    e = TransientStoreUtil.reattach((TransientEntity) e);

    return e == null ? null : e.getNextVersion();
  }

  public static boolean equals(Entity e1, Object e2) {
    if (e1 == null && e2 == null) {
      return true;
    }

    if (e1 == e2) {
      return true;
    }

    if (e1 == null || !(e2 instanceof Entity)) {
      return false;
    }

    //no need to reattach - it's ok to compare entities from different sessions, Entity.equals should handle this situation itself 
    //e1 = TransientStoreUtil.reattach((TransientEntity) e1);
    //e2 = TransientStoreUtil.reattach((TransientEntity) e2);

    return e1.equals(e2);
  }

  /**
   * Slow method! Use with care.
   *
   * @param entities
   * @param i
   * @return
   * @deprecated slow method. for testcases only.
   */
  public static Entity getElement(@NotNull Iterable<Entity> entities, int i) {
    if (log.isWarnEnabled()) {
      log.warn("Slow method getElementOfMultiple() was called!");
    }

    if (entities instanceof EntityIterable) {
      final EntityIterator it = ((EntityIterable) entities).skip(i).iterator();
      if (it.hasNext()) {
        return it.next();
      }
    } else {
      int j = 0;
      for (Entity e : entities) {
        if (i == j++) {
          return e;
        }
      }
    }

    throw new IllegalArgumentException("Out of bounds: " + i);
  }

  public static int getSize(Iterable<Entity> input) {
    if (input instanceof EntityIterable) {
      return (int) ((EntityIterable) input).size();
    }

    if (input instanceof Collection) {
      return ((Collection<Entity>) input).size();
    }

    return ListSequence.fromIterable(input).size();
  }

  public static int count(Iterable<Entity> input) {
    if (input instanceof EntityIterable) {
      return (int) ((EntityIterable) input).count();
    }

    if (log.isDebugEnabled()) {
      log.debug("Brute force calculation of count!", new Exception("Brute force calculation of count!"));
    }

    if (input instanceof Collection) {
      return ((Collection<Entity>) input).size();
    }

    return ListSequence.fromIterable(input).count();
  }

  public static Iterable<Entity> skip(final Iterable<Entity> input, final int elementsToSkip) {
    if (input instanceof EntityIterable) {
      return ((EntityIterable) input).skip(elementsToSkip);
    }

    return ListSequence.fromIterable(input).skip(elementsToSkip);
  }

  public static Iterable<Entity> sort(@NotNull final TransientStoreSession session,
                                      @NotNull final String entityType,
                                      @NotNull final String propertyName,
                                      @Nullable final Iterable<Entity> source,
                                      @NotNull final Comparator<Entity> comparator,
                                      final boolean ascending) {
    // for getAll("") particularly
    if (source == null) {
      return session.sort(entityType, propertyName, ascending);
    }
    // for BerkeleyDb entity iterables and PersistentEntityIterableWrapper
    if (source instanceof EntityIterable && !(source instanceof TransientEntityIterable)) {
      return session.sort(entityType, propertyName, ((EntityIterable) source).getSource(), ascending);
    }
    // for TransientEntityIterable and other Iterable<Entity> instances
    return ListSequence.fromIterable(source).sort(comparator, ascending);
  }

  public static Iterable<Entity> sort(@NotNull final TransientStoreSession session,
                                      @NotNull final String enumType,
                                      @NotNull final String propertyName,
                                      @NotNull final String entityType,
                                      @NotNull final String linkName,
                                      @Nullable final Iterable<Entity> source,
                                      @NotNull final Comparator<Entity> comparator,
                                      final boolean ascending) {
    if (source instanceof EntityIterable && !(source instanceof TransientEntityIterable)) {
      final EntityIterable it = ((EntityIterable) source).getSource();
      final long enumCount = session.getAll(enumType).size();
      final long itCount = it.size();
      if (enumCount < 40 && (itCount > enumCount || itCount < 0)) {
        EntityIterable result = null;
        for (final Entity sortedEnum : session.sort(enumType, propertyName, ascending)) {
          final EntityIterable equal = session.findLinks(entityType, sortedEnum, linkName).getSource().intersect(it);
          if (result == null) {
            result = equal;
          } else {
            result = result.getSource().concat(equal);
          }
        }
        assert result != null;
        return session.createPersistentEntityIterableWrapper(result);
      }
    }
    return ListSequence.fromIterable(source).sort(comparator, ascending);
  }

  public static Iterable<Entity> selectDistinct(@NotNull final TransientStoreSession session,
                                                @NotNull final Iterable<Entity> source,
                                                @NotNull final String linkName) {
    if (source instanceof EntityIterable && !(source instanceof TransientEntityIterable)) {
      return session.selectDistinct(((EntityIterable) source).getSource(), linkName);
    }
    // for TransientEntityIterable and other Iterable<Entity> instances
    return ListSequence.fromIterable(ListSequence.fromIterable(source).select(new ISelector<Entity, Entity>() {
      public Entity select(Entity input) {
        return AssociationSemantics.getToOne(input, linkName);
      }
    })).distinct();
  }

  public static boolean hasChanges(@NotNull TransientEntity e) {
    e = TransientStoreUtil.reattach(e);

    return e == null ? false : e.hasChanges();
  }

  public static boolean hasChanges(@NotNull TransientEntity e, String property) {
    e = TransientStoreUtil.reattach(e);

    return e == null ? false : e.hasChanges(property);
  }

  public static int indexOf(@NotNull Iterable<Entity> it, Entity e) {
    if (e == null) {
      return -1;
    }

    if (it instanceof PersistentEntityIterableWrapper) {
      return ((EntityIterable) it).getSource().indexOf(e);
    }

    return ListSequence.fromIterable(it).indexOf(e);
  }

  public static boolean contains(@NotNull Iterable<Entity> it, Entity e) {
    return indexOf(it, e) >= 0;
  }

}
