package net.inter.spring.sample.microservice.auth.user.dao;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.SneakyThrows;
import net.inter.spring.sample.mapper.auth.user.UserMapper;
import net.inter.spring.sample.microservice.auth.role.entity.QRole;
import net.inter.spring.sample.microservice.auth.user.dto.*;
import net.inter.spring.sample.microservice.auth.user.entity.QUser;
import net.inter.spring.sample.microservice.auth.user.entity.QUserRole;
import net.inter.spring.sample.microservice.auth.user.entity.User;
import net.inter.spring.sample.microservice.auth.organization.dao.OrganizationRepository;
import net.inter.spring.sample.config.security.CustomSecurityValue;



import net.inter.spring.sample.util.CommonConstant;
import net.inter.spring.sample.exception.CustomExceptionUtils;
import net.inter.spring.sample.exception.data.ResourceNotFoundException;

import net.inter.spring.sample.microservice.auth.user.dto.Email;

import net.inter.spring.sample.config.security.bean.AccessTokenUserInfo;
import net.inter.spring.sample.util.CustomUtils;
import net.inter.spring.sample.util.ValidatorUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;


import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.support.QuerydslRepositorySupport;


@Service
public class UserService  extends QuerydslRepositorySupport implements UserDetailsService {

    private final JPAQueryFactory jpaQueryFactory;

    private final UserRepository userRepository;


    public UserService(UserRepository userRepository,
                      @Qualifier("authJpaQueryFactory") JPAQueryFactory jpaQueryFactory) {
        super(User.class);
        this.userRepository = userRepository;
        this.jpaQueryFactory = jpaQueryFactory;
    }

    @Override
    @PersistenceContext(unitName = "authEntityManager")
    public void setEntityManager(EntityManager entityManager) {
        super.setEntityManager(entityManager);
    }

    /*
    *
    *   1. 사용자 조회
    *
    * */

    public User findById(Long id) throws ResourceNotFoundException {
        return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("findById - User not found for this id :: " + id));
    }
    public User findByIdWithOrganizationRole(Long id) {

        final QUser qUser = QUser.user;
        final QUserRole qUserRole = QUserRole.userRole;
        final QRole qRole = QRole.role;

        return jpaQueryFactory.selectFrom(qUser)
                .leftJoin(qUser.userRoles, qUserRole).fetchJoin().leftJoin(qUserRole.role, qRole).fetchJoin()
                .where(qUser.id.eq(id)).fetchOne();

    }
    public User findByEmailWithOrganizationRole(String email) {

        final QUser qUser = QUser.user;
        final QUserRole qUserRole = QUserRole.userRole;
        final QRole qRole = QRole.role;

        return jpaQueryFactory.selectFrom(qUser)
                .leftJoin(qUser.userRoles, qUserRole).fetchJoin().leftJoin(qUserRole.role, qRole).fetchJoin()
                .where(qUser.email.eq(email)).fetchOne();

    }
    public Boolean checkSuperAdminFromAccessTokeUserInfo(AccessTokenUserInfo accessTokenUserInfo) throws ResourceNotFoundException {

        Boolean superAdmin = false;

        Set<String> userRoles = AuthorityUtils.authorityListToSet(accessTokenUserInfo.getAuthorities());

        if (userRoles != null && userRoles.size() > 0) {
            for (String role : userRoles) {
                if (role.equals(CommonConstant.SUPER_ADMIN_ROLE_NAME)) {
                    superAdmin = true;
                }
            }
        }

        return superAdmin;
    }


    // https://velog.io/@jurlring/TransactionalreadOnly-true%EC%97%90%EC%84%9C-readOnly-true%EB%8A%94-%EB%AC%B4%EC%8A%A8-%EC%97%AD%ED%95%A0%EC%9D%B4%EA%B3%A0-%EA%BC%AD-%EC%8D%A8%EC%95%BC%ED%95%A0%EA%B9%8C
    @Transactional(value = "authTransactionManager", readOnly = true)
    public Page<User> findUsersByPageRequest(Boolean skipPagination,
                                             Integer pageNum,
                                             Integer pageSize,
                                             String userSearchFilter,
                                             String sorterValueFilter,
                                             AccessTokenUserInfo accessTokenUserInfo) throws JsonProcessingException, ResourceNotFoundException {

        final QUser qUser = QUser.user;

        JPQLQuery<User> query = jpaQueryFactory.selectFrom(qUser);

        ObjectMapper objectMapper = new ObjectMapper();

        if(!CustomUtils.isEmpty(userSearchFilter)) {


            UserSearchFilter deserializedUserSearchFilter = (UserSearchFilter) objectMapper.readValue(userSearchFilter, UserSearchFilter.class);

            if (!CustomUtils.isEmpty(deserializedUserSearchFilter.getEmail())) {
                query.where(qUser.email.likeIgnoreCase("%" + deserializedUserSearchFilter.getEmail() + "%"));
            }
            if (!CustomUtils.isEmpty(deserializedUserSearchFilter.getName())) {
                query.where(qUser.name.likeIgnoreCase("%" + deserializedUserSearchFilter.getName() + "%"));
            }
     /*       if (!CustomUtils.isEmpty(deserializedUserSearchFilter.getOrganizationName())) {
                builder.and(qUser.organization.name.like("%" + deserializedUserSearchFilter.getOrganizationName() + "%"));
            }*/
        }


        Sort.Direction sortDirection = Sort.Direction.DESC;
        String sortedColumn = "updated_at";
        if(!CustomUtils.isEmpty(sorterValueFilter)) {
            SorterValueFilter deserializedSorterValueFilter = (SorterValueFilter) objectMapper.readValue(sorterValueFilter, SorterValueFilter.class);

            sortDirection = deserializedSorterValueFilter.getAsc() ?  Sort.Direction.ASC : Sort.Direction.DESC;
            sortedColumn = deserializedSorterValueFilter.getColumn();
        }


        // !! IMPORTANT AUTO_ADMIN vs REGISTERED_ADMIN
        // 슈퍼 어드민이 아닌 조직 어드민인 사용자가 사용자들을 조회할 경우, 사용자들은 해당 조직내로 제한 되어야만 한다.
 /*       if (!checkSuperAdminFromAccessTokeUserInfo(accessTokenUserInfo)) {
            builder.and(qUser.organization.id.eq(accessTokenUserInfo.getOrganization_id()));
        }*/

        // Pagination
        if (skipPagination) {
            pageNum = Integer.parseInt(CommonConstant.COMMON_PAGE_NUM);
            pageSize = Integer.parseInt(CommonConstant.COMMON_PAGE_SIZE_DEFAULT_MAX);
        }
        PageRequest pageRequest = PageRequest.of(pageNum - 1, pageSize, Sort.by(sortDirection, sortedColumn));

        return new PageImpl<>(getQuerydsl().applyPagination(pageRequest, query).fetch());

    }



    /*
        2. 사용자 생성
    * */
    public User createUser(User user){
        return userRepository.save(user);
    }



    public User updateResetTokenTime(Email email) {

        // 다른 소스들과의 일관성을 고려해서 사용자가 발견되지 않을 경우 ResourceNotFoundException을 사용한다. UsernameNotfoundException이라고 springframework에서 제공하는
        // Exception이 있는데 이는 500 오류를 발생시킨다. 또한, 프론트에서 이와 같은 경우 어떻게 처리할 지 생각해 보아야 한다.
        User user = userRepository.findByEmail(email.getToAddress()).orElseThrow(() -> new ResourceNotFoundException("User not found."));
        // 현재 시간기준으로 + 3 분을 해서 토큰 유효시간 제한을 둔다.
        LocalDateTime localDateTime = LocalDateTime.now().plusSeconds(180);
        user.setResetTokenTime(localDateTime);

        return user;

    }

    public void handleFailCnt(String userName) throws UsernameNotFoundException {

        User user = userRepository.findByEmail(userName).orElseThrow(() -> new UsernameNotFoundException("Email " + userName + " not found"));
        //현재 로그인 시도 횟수 (기존 누적 횟수+1)
        int currentFailCnt = user.getFail_cnt() == null || user.getFail_cnt() == 0 ? 1 : user.getFail_cnt()+1;

        if(currentFailCnt < CustomSecurityValue.loginMaxRetry.getValue()) {
            user.setFail_cnt(currentFailCnt);
        }else{

            user.setFail_cnt(currentFailCnt);
            user.setActive("0");
        }
    }

    public void resetFailCnt(String userName) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(userName).orElseThrow(() -> new UsernameNotFoundException("Email " + userName + " not found"));
        user.setFail_cnt(0);
    }

    public void sendResetTokenMail(EmailResetToken emailResetToken) {
        String subject ="인증 코드가 도착하였습니다.";
        String content = "<h4>" + emailResetToken.getResetToken()+ "</h4>";

    }

    public void updateActiveStatus(UpdateActiveStatus updateActiveStatus, AccessTokenUserInfo accessTokenUserInfo){
        Long organizationId  = accessTokenUserInfo.getOrganization().getId();
        if(updateActiveStatus.getIsFullFix() ){
            userRepository.updateAllUsersActiveStatus(updateActiveStatus.getActive(), organizationId);
        }else{
            userRepository.updateSelectedUsersActiveStatus(updateActiveStatus.getUserIds() , updateActiveStatus.getActive(), organizationId);
        }
    }



    /*
      5. Oauth2 커스토 마이징
    * */
    @Override
    @SneakyThrows
    @Transactional(value = "authTransactionManager", readOnly = true)
    public UserDetails loadUserByUsername(String userName) throws UsernameNotFoundException{

        User user = userRepository.findByEmail(userName).orElseThrow(() -> new UsernameNotFoundException("Email : " + userName + " not found"));

        return buildUserForAuthentication(user, getAuthorities(user.getId()));

    }


    private AccessTokenUserInfo buildUserForAuthentication(User user, Collection<? extends GrantedAuthority> authorities) {
        String username = user.getEmail();
        String password = user.getPassword().getValue();
        boolean enabled = true;
        boolean accountNonExpired = true;
        boolean credentialsNonExpired = true;
        boolean accountNonLocked = true;

        AccessTokenUserInfo authUser = new AccessTokenUserInfo(username, password, enabled, accountNonExpired, credentialsNonExpired,
                accountNonLocked, authorities);

        authUser.setId(user.getId());
       // authUser.setOrganization_id(user.getOrganization_id());

        return authUser;
    }

    private Collection<? extends GrantedAuthority> getAuthorities(Long userId) {

        User user = findByIdWithOrganizationRole(userId);

        String[] userRoles = user.getUserRoles().stream().map((userRole) -> userRole.getRole().getName()).toArray(String[]::new);
        Collection<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList(userRoles);
        return authorities;
    }

}