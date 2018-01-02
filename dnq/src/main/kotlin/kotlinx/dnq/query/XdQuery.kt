/**
 * Copyright 2006 - 2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kotlinx.dnq.query

import jetbrains.exodus.database.TransientEntity
import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.query.*
import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.session
import kotlinx.dnq.util.entityType
import kotlinx.dnq.util.getDBName
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaType

interface XdQuery<out T : XdEntity> {
    val entityType: XdEntityType<T>
    val entityIterable: Iterable<Entity>
}

class XdQueryImpl<out T : XdEntity>(
        override val entityIterable: Iterable<Entity>,
        override val entityType: XdEntityType<T>) : XdQuery<T>

private val <T : XdEntity> XdQuery<T>.queryEngine: QueryEngine
    get() = entityType.entityStore.queryEngine

fun <T : XdEntity> Iterable<Entity>?.asQuery(entityType: XdEntityType<T>): XdQuery<T> {
    return if (this != null) {
        XdQueryImpl(this, entityType)
    } else {
        XdQueryImpl(EntityIterableBase.EMPTY, entityType)
    }
}

fun <T : XdEntity> XdQuery<T>.asSequence(): Sequence<T> {
    return entityIterable
            .asSequence()
            .map { entityType.wrap(it) }
}

operator fun <T : XdEntity> XdQuery<T>.iterator(): Iterator<T> = this.asSequence().iterator()

fun <T : XdEntity, C : MutableCollection<in T>> XdQuery<T>.toCollection(destination: C) = asSequence().toCollection(destination)

fun <T : XdEntity> XdQuery<T>.toList() = asSequence().toList()

fun <T : XdEntity> XdQuery<T>.toMutableList() = asSequence().toMutableList()

fun <T : XdEntity> XdQuery<T>.toSet() = asSequence().toSet()

fun <T : XdEntity> XdQuery<T>.toHashSet() = asSequence().toHashSet()

fun <T : XdEntity> XdQuery<T>.toSortedSet(comparator: Comparator<T>) = asSequence().toSortedSet(comparator)

fun <T : XdEntity> XdQuery<T>.toMutableSet() = asSequence().toMutableSet()

fun <T : XdEntity> XdEntityType<T>.emptyQuery(): XdQuery<T> {
    val it = StaticTypedIterableDecorator(entityType, EntityIterableBase.EMPTY, entityStore.queryEngine)
    return XdQueryImpl(it, this)
}

private fun <T : XdEntity> XdEntityType<T>.singleton(element: Entity?): Iterable<Entity> {
    if (element == null) {
        return EntityIterableBase.EMPTY
    }
    if ((element as TransientEntity).isNew) {
        return sequenceOf(element).asIterable()
    }
    return entityStore.session.getSingletonIterable(element)
}

fun <T : XdEntity> XdEntityType<T>.queryOf(vararg elements: T?): XdQuery<T> {
    val queryEngine = entityStore.queryEngine
    val union = elements.fold<T?, Iterable<Entity>>(EntityIterableBase.EMPTY) { union, element ->
        if (element != null) {
            queryEngine.union(union, singleton(element.entity))
        } else {
            union
        }
    }
    return XdQueryImpl(union, this)
}

infix fun <T : XdEntity> XdQuery<T>.intersect(that: XdQuery<T>): XdQuery<T> {
    val result = queryEngine.intersect(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

infix fun <T : XdEntity> XdQuery<T>.union(that: XdQuery<T>): XdQuery<T> {
    val result = queryEngine.union(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

infix fun <T : XdEntity> XdQuery<T>.union(that: T?): XdQuery<T> {
    return this union entityType.queryOf(that)
}

operator fun <T : XdEntity> XdQuery<T>.plus(that: XdQuery<T>): XdQuery<T> {
    val result = queryEngine.concat(this.entityIterable, that.entityIterable)
    return XdQueryImpl(result, this.entityType)
}

operator fun <T : XdEntity> XdQuery<T>.plus(that: T?): XdQuery<T> {
    return this + entityType.queryOf(that)
}

infix fun <T : XdEntity> XdQuery<T>.exclude(that: XdQuery<T>): XdQuery<T> {
    val it = queryEngine.exclude(this.entityIterable, that.entityIterable)
    return XdQueryImpl(it, this.entityType)
}

infix fun <T : XdEntity> XdQuery<T>.exclude(that: T?): XdQuery<T> {
    return this exclude entityType.queryOf(that)
}

fun <T : XdEntity> XdQuery<T>.query(node: NodeBase): XdQuery<T> {
    return queryEngine.query(entityIterable, entityType.entityType, node).asQuery(entityType)
}

fun <T : XdEntity> XdEntityType<T>.query(node: NodeBase): XdQuery<T> {
    return all().query(node)
}

fun <T : XdEntity, S : T> XdQuery<T>.filterIsInstance(entityType: XdEntityType<S>): XdQuery<S> {
    val queryEngine = this.queryEngine
    val allOfTargetType = queryEngine.queryGetAll(entityType.entityType)
    return queryEngine.intersect(allOfTargetType, this.entityIterable).asQuery(entityType)
}

fun <T : XdEntity, S : T> XdQuery<T>.filterIsNotInstance(entityType: XdEntityType<S>): XdQuery<T> {
    val queryEngine = this.queryEngine
    return queryEngine.exclude(this.entityIterable, queryEngine.queryGetAll(entityType.entityType)).asQuery(this.entityType)
}

fun <T : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(property: KProperty1<T, V>, asc: Boolean = true): XdQuery<T> {
    return queryEngine.query(entityIterable, entityType.entityType, SortByProperty(null, property.getDBName(entityType), asc)).asQuery(entityType)
}

inline fun <reified T : XdEntity, reified S : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(linkProperty: KProperty1<T, S>, property: KProperty1<S, V>, asc: Boolean = true): XdQuery<T> {
    return sortedBy(T::class, linkProperty, S::class, property, asc)
}

fun <T : XdEntity, S : XdEntity, V : Comparable<*>?> XdQuery<T>.sortedBy(klass: KClass<T>, linkProperty: KProperty1<T, S>, linkKlass: KClass<S>, property: KProperty1<S, V>, asc: Boolean = true): XdQuery<T> {
    return queryEngine.query(entityIterable, entityType.entityType, SortByLinkProperty(null, linkKlass.java.entityType.entityType, property.getDBName(linkKlass), linkProperty.getDBName(klass), asc)).asQuery(entityType)
}

fun <T : XdEntity> XdQuery<T>?.size(): Int {
    val it = this?.entityIterable?.let {
        if (it is StaticTypedEntityIterable) {
            it.instantiate()
        } else {
            it
        }
    }

    return when (it) {
        null -> 0
        EntityIterableBase.EMPTY -> 0
        is EntityIterable -> it.size().toInt()
        is Collection<*> -> it.size
        else -> it.count()
    }
}

fun <T : XdEntity> XdQuery<T>?.roughSize(): Int {
    return if (this == null) {
        0
    } else {
        val it = queryEngine.toEntityIterable(entityIterable)
        when (it) {
            is EntityIterable -> it.roughSize.toInt()
            is Collection<*> -> it.size
            else -> it.count()
        }
    }
}

fun <T : XdEntity> XdQuery<T>?.size(node: NodeBase): Int {
    return this?.query(node).size()
}

val <T : XdEntity> XdQuery<T>?.isEmpty: Boolean
    get() {
        return if (this == null) {
            true
        } else {
            val it = entityIterable
            if (it is Collection<*>) {
                it.isEmpty()
            } else {
                val entIt = queryEngine.toEntityIterable(it)
                if (queryEngine.isPersistentIterable(entIt)) {
                    (entIt as EntityIterable).isEmpty
                } else {
                    entIt.none()
                }
            }
        }
    }

val <T : XdEntity> XdQuery<T>?.isNotEmpty: Boolean
    get() = !isEmpty

fun <T : XdEntity> XdQuery<T>.drop(n: Int): XdQuery<T> {
    return operation({ it.skip(n) }, { it.drop(n) })
}

fun <T : XdEntity> XdQuery<T>.take(n: Int): XdQuery<T> {
    return operation({ it.take(n) }, { it.take(n) })
}

private inline fun <T : XdEntity> XdQuery<T>.operation(
        ifEntityIterable: (EntityIterable) -> EntityIterable,
        notEntityIterable: (Sequence<Entity>) -> Sequence<Entity>): XdQuery<T> {
    val it = queryEngine.toEntityIterable(entityIterable)
    return when (it) {
        is EntityIterableBase -> wrap(ifEntityIterable(it.source))
        is EntityIterable -> wrap(ifEntityIterable(it))
        else -> notEntityIterable(it.asSequence()).asIterable()
    }.asQuery(entityType)
}

private fun <T : XdEntity> XdQuery<T>.wrap(entityIterable: EntityIterable): EntityIterable {
    return entityType.entityStore.session.createPersistentEntityIterableWrapper(entityIterable)
}

fun <T : XdEntity> XdQuery<T>.distinct(): XdQuery<T> {
    return operation({ it.distinct() }, { it.distinct() })
}

fun <S : XdEntity, T : XdEntity> XdQuery<S>.mapDistinct(dbFieldName: String, targetEntityType: XdEntityType<T>): XdQuery<T> {
    return queryEngine.selectDistinct(entityIterable, dbFieldName).filterNotNull(targetEntityType).asQuery(targetEntityType)
}

private fun Iterable<Entity?>.filterNotNull(entityType: XdEntityType<*>): Iterable<Entity> {
    val entityTypeName = entityType.entityType
    val queryEngine = entityType.entityStore.queryEngine
    val staticTypedIterable = this as? StaticTypedEntityIterable ?: StaticTypedIterableDecorator(entityTypeName, this, queryEngine)
    return ExcludeNullStaticTypedEntityIterable(entityTypeName, staticTypedIterable, queryEngine)
}

fun <S : XdEntity, T : XdEntity> XdQuery<S>.mapDistinct(field: KProperty1<S, T?>): XdQuery<T> {
    @Suppress("UNCHECKED_CAST")
    return mapDistinct(field.getDBName(entityType), (field.returnType.javaType as Class<T>).entityType)
}

fun <S : XdEntity, T : XdEntity> XdQuery<S>.flatMapDistinct(dbFieldName: String, targetEntityType: XdEntityType<T>): XdQuery<T> {
    return queryEngine.selectManyDistinct(entityIterable, dbFieldName).filterNotNull(targetEntityType).asQuery(targetEntityType)
}

inline fun <S : XdEntity, reified T : XdEntity, Q : XdQuery<T>> XdQuery<S>.flatMapDistinct(field: KProperty1<S, Q>): XdQuery<T> {
    @Suppress("UNCHECKED_CAST")
    return flatMapDistinct(field.getDBName(entityType), T::class.entityType)
}

fun <T : XdEntity> XdQuery<T>.indexOf(entity: Entity?): Int {
    val it = queryEngine.toEntityIterable(entityIterable)
    return if (entity != null) {
        if (queryEngine.isPersistentIterable(it)) {
            (it as EntityIterableBase).source.indexOf(entity)
        } else {
            it.indexOf(entity)
        }
    } else {
        -1
    }
}

fun <T : XdEntity> XdQuery<T>.indexOf(entity: T?): Int {
    return indexOf(entity?.entity)
}

operator fun <T : XdEntity> XdQuery<T>.contains(entity: Entity?): Boolean {
    val i = entityIterable
    return if (i is Collection<*>) {
        i.contains(entity)
    } else {
        indexOf(entity) >= 0
    }
}

operator fun <T : XdEntity> XdQuery<T>.contains(entity: T?): Boolean {
    return contains(entity?.entity)
}

fun <T : XdEntity> XdQuery<T>.first(): T {
    return firstOrNull() ?: throw NoSuchElementException("Query is empty.")
}

fun <T : XdEntity> XdQuery<T>.first(node: NodeBase): T {
    return query(node).first()
}

fun <T : XdEntity> XdQuery<T>.firstOrNull(): T? {
    val it = queryEngine.toEntityIterable(entityIterable)
    return if (it is EntityIterableBase) {
        it.source.first?.let {
            entityType.entityStore.session.newEntity(it)
        }
    } else {
        it.firstOrNull()
    }?.let {
        entityType.wrap(it)
    }
}

fun <T : XdEntity> XdQuery<T>.firstOrNull(node: NodeBase): T? {
    return query(node).firstOrNull()
}

fun <T : XdEntity> XdQuery<T>.single(): T {
    return asSequence().single()
}

fun <T : XdEntity> XdQuery<T>.single(node: NodeBase): T {
    return query(node).single()
}

fun <T : XdEntity> XdQuery<T>.singleOrNull(): T? {
    return asSequence().singleOrNull()
}

fun <T : XdEntity> XdQuery<T>.singleOrNull(node: NodeBase): T? {
    return query(node).singleOrNull()
}

fun <T : XdEntity> XdQuery<T>.any() = isNotEmpty

fun <T : XdEntity> XdQuery<T>.any(node: NodeBase): Boolean {
    return query(node).any()
}

fun <T : XdEntity> XdQuery<T>.none() = isEmpty

fun <T : XdEntity> XdQuery<T>.none(node: NodeBase): Boolean {
    return query(node).asSequence().none()
}

fun <T : XdEntity> XdMutableQuery<T>.addAll(elements: Sequence<T>) {
    elements.forEach { add(it) }
}

fun <T : XdEntity> XdMutableQuery<T>.addAll(elements: XdQuery<T>) {
    addAll(elements.asSequence())
}

fun <T : XdEntity> XdMutableQuery<T>.addAll(elements: Iterable<T>) {
    addAll(elements.asSequence())
}

fun <T : XdEntity> XdMutableQuery<T>.removeAll(elements: Sequence<T>) {
    elements.forEach { remove(it) }
}

fun <T : XdEntity> XdMutableQuery<T>.removeAll(elements: XdQuery<T>) {
    removeAll(elements.asSequence())
}

fun <T : XdEntity> XdMutableQuery<T>.removeAll(elements: Iterable<T>) {
    removeAll(elements.asSequence())
}