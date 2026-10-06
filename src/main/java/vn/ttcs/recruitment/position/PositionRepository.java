package vn.ttcs.recruitment.position;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, UUID> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id = :id")
    Optional<Position> findByIdForUpdate(@Param("id") UUID id);

    // Parameters are never null: PostgreSQL cannot infer a type for a null JPQL parameter (lower(bytea) error).
    // The pattern is already escaped with '!', so % and _ typed by users stay literal.
    @Query("""
            select p from Position p
            where (lower(p.code) like lower(:pattern) escape '!' or lower(p.name) like lower(:pattern) escape '!')
              and p.active in :activeValues
            """)
    Page<Position> search(@Param("pattern") String pattern, @Param("activeValues") Collection<Boolean> activeValues,
                          Pageable pageable);
}
