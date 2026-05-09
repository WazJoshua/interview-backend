package com.josh.interviewj.common.settings.repository;

import com.josh.interviewj.common.settings.model.SystemSettingRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SystemSettingRevisionRepository extends JpaRepository<SystemSettingRevision, String> {

    @Transactional
    @Modifying
    @Query("""
            UPDATE SystemSettingRevision revision
               SET revision.currentRevision = revision.currentRevision + 1,
                   revision.updatedAt = CURRENT_TIMESTAMP
             WHERE revision.singletonKey = :singletonKey
               AND revision.currentRevision = :expectedRevision
            """)
    int bumpRevisionIfExpected(
            @Param("singletonKey") String singletonKey,
            @Param("expectedRevision") Long expectedRevision
    );
}
