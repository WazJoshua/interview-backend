package com.josh.interviewj.chat.repository;

import com.josh.interviewj.chat.model.ChatEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatEventRepository extends JpaRepository<ChatEvent, Long> {
}
