package vn.ttcs.recruitment.competency;

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

public interface CompetencyFrameworkRepository extends JpaRepository<CompetencyFramework, UUID> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    // SELECT ... FOR UPDATE: every write of a framework or its criteria locks the framework row first,
    // so two edits of the same framework run one after the other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from CompetencyFramework f where f.id = :id")
    Optional<CompetencyFramework> findByIdForUpdate(@Param("id") UUID id);

    // Same rules as PositionRepository.search: parameters are never null, and the pattern is already escaped
    // with '!', so % and _ typed by users stay literal.
    @Query("""
            select f from CompetencyFramework f
            where (lower(f.code) like lower(:pattern) escape '!' or lower(f.name) like lower(:pattern) escape '!')
              and f.status in :statuses
            """)
    Page<CompetencyFramework> search(@Param("pattern") String pattern,
                                     @Param("statuses") Collection<CompetencyFrameworkStatus> statuses,
                                     Pageable pageable);
}
