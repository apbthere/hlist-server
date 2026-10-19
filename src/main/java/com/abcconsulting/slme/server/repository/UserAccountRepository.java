package com.abcconsulting.slme.server.repository;

import com.abcconsulting.slme.server.model.UserAccount;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends CrudRepository<UserAccount, String> {

	@Query("SELECT * FROM users WHERE LOWER(username) = LOWER(:username)")
	List<UserAccount> findAllByUsernameIgnoringCase(@Param("username") String username);

	/**
	 * The account for a username typed in any capitalization ("alex" finds "Alex"). An exact match wins; otherwise
	 * the case-insensitive match must be unique, so two accounts differing only in case are never confused.
	 */
	default Optional<UserAccount> findByUsernameIgnoringCase(String username) {
		Optional<UserAccount> exact = findById(username);
		if (exact.isPresent()) {
			return exact;
		}
		List<UserAccount> matches = findAllByUsernameIgnoringCase(username);
		return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
	}
}
