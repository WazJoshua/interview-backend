package com.josh.interviewj.common.settings.repository;

import com.josh.interviewj.common.settings.model.SystemSetting;
import com.josh.interviewj.common.settings.model.SystemSettingKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, Long> {

    Optional<SystemSetting> findBySettingKey(SystemSettingKey settingKey);

    List<SystemSetting> findBySettingKeyIn(Collection<SystemSettingKey> settingKeys);
}
