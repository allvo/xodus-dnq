package jetbrains.teamsys.dnq.runtime.events;

/*Generated by MPS */

import jetbrains.exodus.database.Entity;

public abstract class EntityAdapter<T extends Entity> implements IEntityListener<T> {
  public EntityAdapter() {
  }

  public void addedAsync(T added) {
  }

  public void addedSync(T added) {
  }

  public void addedSyncBeforeFlush(T added) {
  }

  public void addedSyncBeforeConstraints(T added) {
  }

  public void updatedAsync(T old, T current) {
  }

  public void updatedSync(T old, T current) {
  }

  public void updatedSyncBeforeFlush(T old, T current) {
  }

  public void updatedSyncBeforeConstraints(T old, T current) {
  }

  public void removedAsync(T removed) {
  }

  public void removedSync(T removed) {
  }

  public void removedSyncBeforeFlush(T removed) {
  }

  public void removedSyncBeforeConstraints(T removed) {
  }
}
