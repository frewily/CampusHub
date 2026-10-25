package io.github.frewily.campushub.identity.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.identity.application.LoginAccount;
import io.github.frewily.campushub.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static io.github.frewily.campushub.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MyBatisLoginAccountRepositoryTest {
    private static final String PHONE = "13800138000";

    @Mock private UserMapper users;
    private MyBatisLoginAccountRepository accounts;

    @BeforeEach
    void setUp() { accounts = new MyBatisLoginAccountRepository(users); }

    @Test
    void findsByBoundPhoneAndMapsEveryAccountFieldWithoutFilteringStatus() {
        long largeId = Long.MAX_VALUE - 17;
        when(users.selectOne(any())).thenReturn(new User().setId(largeId).setNickName("")
                .setIcon("icon").setStatus("DISABLED"));

        LoginAccount account = accounts.findByPhone(PHONE);

        ArgumentCaptor<QueryWrapper<User>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(users).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("phone"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(PHONE));
        assertEquals(largeId, account.getId());
        assertEquals("", account.getNickName());
        assertEquals("icon", account.getIcon());
        assertEquals("DISABLED", account.getStatus());
    }

    @Test
    void missingAccountMapsToNull() {
        when(users.selectOne(any())).thenReturn(null);
        assertNull(accounts.findByPhone(PHONE));
    }

    @Test
    void preservesNullStatusAndNullOrEmptyFields() {
        when(users.selectOne(any())).thenReturn(new User().setId(3L).setNickName(null).setIcon(null).setStatus(null));
        LoginAccount account = accounts.findByPhone(PHONE);
        assertEquals(3L, account.getId());
        assertNull(account.getNickName());
        assertNull(account.getIcon());
        assertNull(account.getStatus());

        when(users.selectOne(any())).thenReturn(new User().setId(4L).setNickName("").setIcon("").setStatus("ACTIVE"));
        account = accounts.findByPhone(PHONE);
        assertEquals("", account.getNickName());
        assertEquals("", account.getIcon());
    }

    @Test
    void createsRandomPrefixedActiveAccountAndUsesGeneratedIdForDefaultRole() {
        when(users.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(901L);
            return 1;
        });
        when(users.insertDefaultUserRole(901L)).thenReturn(1);

        LoginAccount account = accounts.createWithPhone(PHONE);

        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        InOrder order = inOrder(users);
        order.verify(users).insert(inserted.capture());
        order.verify(users).insertDefaultUserRole(901L);
        User user = inserted.getValue();
        assertEquals(PHONE, user.getPhone());
        assertTrue(user.getNickName().startsWith(USER_NICK_NAME_PREFIX));
        assertEquals(10, user.getNickName().length() - USER_NICK_NAME_PREFIX.length());
        assertEquals("ACTIVE", user.getStatus());
        assertEquals(901L, account.getId());
        assertEquals(user.getNickName(), account.getNickName());
        assertEquals(user.getIcon(), account.getIcon());
        assertEquals("ACTIVE", account.getStatus());
        verify(users, times(1)).insert(any(User.class));
    }

    @Test
    void ignoresZeroAffectedRowsFromBothInsertOperations() {
        when(users.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(902L);
            return 0;
        });
        when(users.insertDefaultUserRole(902L)).thenReturn(0);
        LoginAccount account = accounts.createWithPhone(PHONE);
        assertEquals(902L, account.getId());
        verify(users).insertDefaultUserRole(902L);
    }

    @Test
    void propagatesInsertFailureAndDoesNotAttemptRoleInsert() {
        RuntimeException failure = new RuntimeException("account insert");
        when(users.insert(any(User.class))).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> accounts.createWithPhone(PHONE)));
        verify(users, never()).insertDefaultUserRole(anyLong());
    }

    @Test
    void propagatesDefaultRoleFailureAfterAccountInsert() {
        RuntimeException failure = new RuntimeException("role insert");
        when(users.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(903L);
            return 1;
        });
        when(users.insertDefaultUserRole(903L)).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> accounts.createWithPhone(PHONE)));
        InOrder order = inOrder(users);
        order.verify(users).insert(any(User.class));
        order.verify(users).insertDefaultUserRole(903L);
    }
}
