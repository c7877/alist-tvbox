package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.AListLogin;
import cn.har01d.alist_tvbox.dto.AccountDto;
import cn.har01d.alist_tvbox.dto.CheckinLog;
import cn.har01d.alist_tvbox.dto.CheckinResponse;
import cn.har01d.alist_tvbox.dto.CheckinResult;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.model.AListUser;
import cn.har01d.alist_tvbox.model.LoginRequest;
import cn.har01d.alist_tvbox.model.LoginResponse;
import cn.har01d.alist_tvbox.model.UserResponse;
import cn.har01d.alist_tvbox.storage.AliyundriveOpen;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.IdUtils;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static cn.har01d.alist_tvbox.util.Constants.ACCESS_TOKEN;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_LOGIN;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_PASSWORD;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_USERNAME;
import static cn.har01d.alist_tvbox.util.Constants.ALI_SECRET;
import static cn.har01d.alist_tvbox.util.Constants.ATV_PASSWORD;
import static cn.har01d.alist_tvbox.util.Constants.AUTO_CHECKIN;
import static cn.har01d.alist_tvbox.util.Constants.CHECKIN_DAYS;
import static cn.har01d.alist_tvbox.util.Constants.CHECKIN_TIME;
import static cn.har01d.alist_tvbox.util.Constants.FOLDER_ID;
import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN;
import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN_TIME;
import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN_URL;
import static cn.har01d.alist_tvbox.util.Constants.REFRESH_TOKEN;
import static cn.har01d.alist_tvbox.util.Constants.REFRESH_TOKEN_TIME;
import static cn.har01d.alist_tvbox.util.Constants.SCHEDULE_TIME;
import static cn.har01d.alist_tvbox.util.Constants.SHOW_MY_ALI;
import static cn.har01d.alist_tvbox.util.Constants.ZONE_ID;

@Slf4j
@Service
public class AccountService {
    public static final ZoneOffset ZONE_OFFSET = ZoneOffset.of("+08:00");
    public static final int IDX = 4600;
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final AListLocalService aListLocalService;
    private final IndexService indexService;
    private final RestTemplate aListClient;
    private final RestTemplate restTemplate;
    private final TaskScheduler scheduler;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate alistJdbcTemplate;
    private final AppProperties appProperties;
    private ScheduledFuture<?> scheduledFuture;

    public AccountService(AccountRepository accountRepository,
                          SettingRepository settingRepository,
                          AListLocalService aListLocalService,
                          IndexService indexService,
                          AppProperties appProperties,
                          TaskScheduler scheduler,
                          RestTemplateBuilder builder,
                          ObjectMapper objectMapper,
                          @Qualifier("alistJdbcTemplate") JdbcTemplate alistJdbcTemplate) {
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.aListLocalService = aListLocalService;
        this.indexService = indexService;
        this.appProperties = appProperties;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
        this.alistJdbcTemplate = alistJdbcTemplate;
        this.aListClient = builder.rootUri("http://localhost:" + aListLocalService.getInternalPort()).build();
        this.restTemplate = builder.build();
    }

    @PostConstruct
    public void setup() {
        if (!settingRepository.existsById(ALI_SECRET)) {
            settingRepository.save(new Setting(ALI_SECRET, UUID.randomUUID().toString().replace("-", "")));
        }
        if (!settingRepository.existsByName("fix_ali_concurrency")) {
            fixConcurrency();
        }
        if (!settingRepository.existsByName("fix_ali_chunk_size")) {
            fixChunkSize();
        }
        if (!settingRepository.existsByName("security_hardening")) {
            securityHardening();
        }
        scheduleAutoCheckinTime();

        if (accountRepository.count() == 0) {
            String refreshToken = settingRepository.findById(REFRESH_TOKEN).map(Setting::getValue).orElse("");
            String openToken = settingRepository.findById(OPEN_TOKEN).map(Setting::getValue).orElse("");
            Account account = new Account();

            if (StringUtils.isAllBlank(refreshToken, openToken)) {
                log.info("load account from files");
                refreshToken = readRefreshToken();
                openToken = readOpenToken();
            } else {
                log.info("load account from settings");
                settingRepository.deleteById(REFRESH_TOKEN);
                settingRepository.deleteById(OPEN_TOKEN);
                settingRepository.deleteById(FOLDER_ID);
                account.setRefreshTokenTime(settingRepository.findById(REFRESH_TOKEN_TIME).map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setOpenTokenTime(settingRepository.findById(OPEN_TOKEN_TIME).map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setCheckinTime(settingRepository.findById(CHECKIN_TIME).map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setCheckinDays(settingRepository.findById(CHECKIN_DAYS).map(Setting::getValue).map(Integer::parseInt).orElse(0));
                account.setAutoCheckin(settingRepository.findById(AUTO_CHECKIN).map(Setting::getValue).map(Boolean::valueOf).orElse(false));
                account.setShowMyAli(settingRepository.findById(SHOW_MY_ALI).map(Setting::getValue).map(Boolean::valueOf).orElse(false));
            }

            account.setRefreshToken(refreshToken);
            account.setOpenToken(openToken);
            account.setMaster(true);

            if (!StringUtils.isAllBlank(refreshToken, openToken)) {
                accountRepository.save(account);
            } else {
                log.warn("No account");
            }
            readLogin();
        }

        if (accountRepository.count() > 0) {
            try {
                updateAliAccountId();
            } catch (Exception e) {
                log.warn("", e);
            }

            try {
                updateTokens();
            } catch (Exception e) {
                log.warn("", e);
            }

            try {
                enableMyAli();
            } catch (Exception e) {
                log.warn("", e);
            }
        }

        try {
            addAdminUser();
            enableLogin();
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void fixConcurrency() {
        List<Account> accounts = accountRepository.findAll();
        for (Account account : accounts) {
            account.setConcurrency(4);
        }
        accountRepository.saveAll(accounts);
        settingRepository.save(new Setting("fix_ali_concurrency", ""));
    }

    private void fixChunkSize() {
        List<Account> accounts = accountRepository.findAll();
        for (Account account : accounts) {
            account.setChunkSize(256);
        }
        accountRepository.saveAll(accounts);
        settingRepository.save(new Setting("fix_ali_chunk_size", ""));
    }

    private void updateAliAccountId() {
        accountRepository.getFirstByMasterTrue().map(Account::getId).ifPresent(id -> {
            int storageId = IDX + (id - 1) * 2;
            log.info("updateAliAccountId {}", storageId);
            aListLocalService.setSetting("ali_account_id", String.valueOf(storageId), "number");
        });
    }

    private void addAdminUser() {
        try {
            String sql = "DELETE FROM x_users WHERE username = 'atv'";
            aListLocalService.executeUpdate(sql);
            sql = "INSERT INTO x_users (id,username,password,base_path,role,permission) VALUES(4,'atv',\"" + generatePassword() + "\",'/',2,16383)";
            aListLocalService.executeUpdate(sql);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public String generatePassword() {
        Setting setting = settingRepository.findById(ATV_PASSWORD).orElse(null);
        if (setting == null) {
            log.info("generate new password");
            setting = new Setting(ATV_PASSWORD, IdUtils.generate(12));
            settingRepository.save(setting);
        }
        return setting.getValue();
    }

    public String resetPassword() {
        log.info("generate new password");
        String password = IdUtils.generate(12);
        settingRepository.save(new Setting(ATV_PASSWORD, password));
        String sql = "UPDATE x_users SET password = '" + password + "' WHERE username = 'atv'";
        aListLocalService.executeUpdate(sql);
        return password;
    }

    private String readRefreshToken() {
        Path path = Utils.getDataPath("mytoken.txt");
        if (Files.exists(path)) {
            try {
                log.info("read refresh token from file");
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    return lines.get(0).trim();
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return "";
    }

    private String readOpenToken() {
        Path path = Utils.getDataPath("myopentoken.txt");
        if (Files.exists(path)) {
            try {
                log.info("read open token from file");
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    return lines.get(0).trim();
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return "";
    }

    private void readLogin() {
        try {
            String password = settingRepository.findById(ALIST_PASSWORD).map(Setting::getValue).orElse(null);
            if (password != null) {
                return;
            }

            AListLogin login = new AListLogin();
            Path pass = Utils.getDataPath("guestpass.txt");
            if (Files.exists(pass)) {
                log.info("read guest password from file");
                List<String> lines = Files.readAllLines(pass);
                if (!lines.isEmpty()) {
                    login.setUsername("guest");
                    login.setPassword(lines.get(0));
                    login.setEnabled(true);
                }
            }

            Path guest = Utils.getDataPath("guestlogin.txt");
            if (Files.exists(guest)) {
                log.info("guestlogin.txt");
                login.setUsername("dav");
                login.setEnabled(true);
            }

            settingRepository.save(new Setting(ALIST_USERNAME, login.getUsername()));
            settingRepository.save(new Setting(ALIST_PASSWORD, login.getPassword()));
            settingRepository.save(new Setting(ALIST_LOGIN, String.valueOf(login.isEnabled())));
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void scheduleAutoCheckinTime() {
        try {
            LocalTime localTime = getScheduleTime();
            log.info("schedule time: {}", localTime);
            scheduledFuture = scheduler.schedule(this::handleScheduleTask, new CronTrigger(String.format("%d %d %d * * ?", localTime.getSecond(), localTime.getMinute(), localTime.getHour())));
            if (LocalTime.now().isAfter(localTime)) {
                autoCheckin();
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private LocalTime getScheduleTime() {
        String time = settingRepository.findById(SCHEDULE_TIME).map(Setting::getValue).orElse(null);
        LocalTime localTime = LocalTime.of(9, 0, 0);
        if (time != null) {
            try {
                localTime = Instant.parse(time).atZone(ZoneId.of(ZONE_ID)).toLocalTime();
            } catch (Exception e) {
                log.warn("", e);
                settingRepository.save(new Setting(SCHEDULE_TIME, "2023-07-31T00:00:00.000Z"));
            }
        }
        return localTime;
    }

    public void autoCheckin() {
        for (Account account : accountRepository.findAll()) {
            if (account.isAutoCheckin()) {
                try {
                    checkin(account, false);
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        }
    }

    public void handleScheduleTask() {
        log.info("handleScheduleTask");
        for (Account account : accountRepository.findAll()) {
            try {
                refreshTokens(account);
            } catch (Exception e) {
                log.warn("", e);
            }

            if (account.isAutoCheckin()) {
                try {
                    checkin(account, true);
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        }

        indexService.getRemoteVersion();
    }

    private boolean shouldRefreshOpenToken(Account account) {
        if (StringUtils.isBlank(account.getOpenToken())) {
            return false;
        }
        if (account.getOpenTokenTime() == null) {
            return true;
        }
        try {
            String json = account.getOpenToken().split("\\.")[1];
            byte[] bytes = Base64.getDecoder().decode(json);
            JsonNode map = objectMapper.readTree(bytes);
            log.debug("open token: {}", map);
            int exp = map.get("exp").asInt();
            Instant expireTime = Instant.ofEpochSecond(exp).plus(3, ChronoUnit.DAYS);
            return expireTime.isAfter(Instant.now());
        } catch (Exception e) {
            log.warn("", e);
        }
        return true;
    }

    private void refreshTokens(Account account) {
        boolean changed = false;
        Instant now = Instant.now().plusSeconds(60);
        Instant time;
        try {
            if (shouldRefreshOpenToken(account)) {
                log.info("update open refresh token {}: {}", account.getId(), account.getRefreshTokenTime());
                account.setOpenTokenTime(Instant.now());
                account.setOpenAccessTokenTime(Instant.now());
                Map<Object, Object> response = getAliOpenToken(account.getOpenToken());
                account.setOpenToken((String) response.get(REFRESH_TOKEN));
                account.setOpenAccessToken((String) response.get(ACCESS_TOKEN));
                changed = true;
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            time = account.getRefreshTokenTime();
            if ((time == null || time.plus(1, ChronoUnit.DAYS).isAfter(now)) && account.getRefreshToken() != null) {
                log.info("update refresh token {}: {}", account.getId(), time);
                account.setRefreshTokenTime(Instant.now());
                Map<Object, Object> response = getAliToken(account.getRefreshToken());
                account.setNickname((String) response.get("nick_name"));
                account.setRefreshToken((String) response.get(REFRESH_TOKEN));
                account.setAccessToken((String) response.get(ACCESS_TOKEN));
                changed = true;
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        if (changed) {
            accountRepository.save(account);
            updateTokenToAList(account);
        }
    }

    public Map<Object, Object> getAliToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, appProperties.getUserAgent());
        headers.set(HttpHeaders.REFERER, Constants.ALIPAN);
        Map<String, String> body = new HashMap<>();
        body.put(REFRESH_TOKEN, token);
        body.put("grant_type", REFRESH_TOKEN);
        log.debug("body: {}", body);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange("https://auth.aliyundrive.com/v2/account/token", HttpMethod.POST, entity, Map.class);
        log.debug("get Ali token response: {}", response.getBody());
        return response.getBody();
    }

    public Map<Object, Object> getAliOpenToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, appProperties.getUserAgent());
        headers.set(HttpHeaders.REFERER, "https://xhofe.top/");
        Map<String, String> body = new HashMap<>();
        body.put(REFRESH_TOKEN, token);
        body.put("client_id", settingRepository.findById("open_api_client_id").map(Setting::getValue).orElse(""));
        body.put("client_secret", settingRepository.findById("open_api_client_secret").map(Setting::getValue).orElse(""));
        body.put("grant_type", REFRESH_TOKEN);
        log.debug("body: {}", body);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        String url = settingRepository.findById(OPEN_TOKEN_URL).map(Setting::getValue).orElse("https://ali.har01d.org/access_token");
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
        log.debug("get open token response: {}", response.getBody());
        return response.getBody();
    }

    private void securityHardening() {
        String username = settingRepository.findById(ALIST_USERNAME).map(Setting::getValue).orElse("");
        String password = settingRepository.findById(ALIST_PASSWORD).map(Setting::getValue).orElse("");
        if (username.isEmpty() || password.isEmpty()) {
            settingRepository.save(new Setting(ALIST_LOGIN, "true"));
            settingRepository.save(new Setting(ALIST_USERNAME, Utils.generateUsername()));
            settingRepository.save(new Setting(ALIST_PASSWORD, Utils.generateSecurePassword()));
        }
        settingRepository.save(new Setting("security_hardening", ""));
    }

    public void enableLogin() {
        AListLogin login = new AListLogin();
        login.setEnabled(settingRepository.findById(ALIST_LOGIN).map(Setting::getValue).orElse("").equals("true"));
        login.setUsername(settingRepository.findById(ALIST_USERNAME).map(Setting::getValue).orElse(""));
        login.setPassword(settingRepository.findById(ALIST_PASSWORD).map(Setting::getValue).orElse(""));

        try {
            String sql;
            if (!aListLocalService.existsById("x_users", 2)) {
                sql = "INSERT INTO x_users (id,username,password,base_path,role,permission) VALUES (2,'guest','alist_tvbox','/',1,256)";
                aListLocalService.executeUpdate(sql);
            }

            sql = "update x_users set disabled = 1 where username = 'admin'";
            aListLocalService.executeUpdate(sql);
            if (login.isEnabled()) {
                log.info("enable AList login: {}", login.getUsername());
                if (login.getUsername().equals("guest")) {
                    sql = "delete from x_users where id = 3";
                    aListLocalService.executeUpdate(sql);
                    sql = "update x_users set disabled = 0 where username = 'guest'";
                    aListLocalService.executeUpdate(sql);
                } else {
                    sql = "update x_users set disabled = 1 where username = 'guest'";
                    aListLocalService.executeUpdate(sql);
                    sql = "delete from x_users where id = 3";
                    aListLocalService.executeUpdate(sql);
                    sql = "INSERT INTO x_users (id,username,password,base_path,role,permission) VALUES (3,'" + login.getUsername() + "','" + login.getPassword() + "','/',0,372)";
                    aListLocalService.executeUpdate(sql);
                }
            } else {
                log.info("enable AList guest");
                sql = "update x_users set disabled = 0, permission = 368, password = 'alist_tvbox' where username = 'guest'";
                aListLocalService.executeUpdate(sql);
                sql = "delete from x_users where id = 3";
                aListLocalService.executeUpdate(sql);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        log.info("{} AList user {}", login.isEnabled() ? "enable" : "disable", login.getUsername());
    }

    public void enableMyAli() {
        List<Account> list = accountRepository.findAll();
        log.debug("enableMyAli {}", list.size());
        try {
            for (Account account : list) {
                try {
                    int id = IDX + (account.getId() - 1) * 2;
                    String name = account.getNickname();
                    if (StringUtils.isBlank(name)) {
                        name = String.valueOf(account.getId());
                    }
                    String sql;
                    if (account.isShowMyAli() || account.isMaster() || list.size() == 1) {
                        AliyundriveOpen storage = new AliyundriveOpen(account, "resource");
                        aListLocalService.saveStorage(storage);
                        storage = new AliyundriveOpen(account, "backup");
                        aListLocalService.saveStorage(storage);
                    } else {
                        sql = "DELETE FROM x_storages WHERE id = " + id;
                        aListLocalService.executeUpdate(sql);
                        log.info("remove AList storage {} {}", id, name);
                        sql = "DELETE FROM x_storages WHERE id = " + (id + 1);
                        aListLocalService.executeUpdate(sql);
                        log.info("remove AList storage {} {}", id, name);
                    }
                    log.info("enableMyAli {}", account.isShowMyAli() || account.isMaster());
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public void updateTokens() {
        aListLocalService.executeUpdate("CREATE TABLE IF NOT EXISTS \"x_tokens\" (`key` text,`value` text,`account_id` integer,`modified` datetime,PRIMARY KEY (`key`))");
        List<Account> list = accountRepository.findAll();
        log.info("updateTokens {}", list.size());
        for (Account account : list) {
            String sql = "INSERT INTO x_tokens VALUES('RefreshToken-%d','%s',%d,'%s')";
            aListLocalService.executeUpdate(String.format(sql, account.getId(), account.getRefreshToken(), account.getId(), getTime(account.getRefreshTokenTime())));
            sql = "INSERT INTO x_tokens VALUES('RefreshTokenOpen-%d','%s',%d,'%s')";
            aListLocalService.executeUpdate(String.format(sql, account.getId(), account.getOpenToken(), account.getId(), getTime(account.getOpenTokenTime())));
            if (StringUtils.isNotBlank(account.getAccessToken())) {
                sql = "INSERT INTO x_tokens VALUES('AccessToken-%d','%s',%d,'%s')";
                aListLocalService.executeUpdate(String.format(sql, account.getId(), account.getAccessToken(), account.getId(), getTime(account.getAccessTokenTime())));
            }
            if (StringUtils.isNotBlank(account.getOpenAccessToken())) {
                sql = "INSERT INTO x_tokens VALUES('AccessTokenOpen-%d','%s',%d,'%s')";
                aListLocalService.executeUpdate(String.format(sql, account.getId(), account.getOpenAccessToken(), account.getId(), getTime(account.getOpenAccessTokenTime())));
            }
        }
    }

    private OffsetDateTime getTime(Instant time) {
        if (time == null) {
            return OffsetDateTime.now();
        }
        return time.atOffset(ZONE_OFFSET);
    }

    public AListLogin updateAListLogin(AListLogin login) {
        aListLocalService.validateAListStatus();
        if (login.isEnabled()) {
            if (StringUtils.isBlank(login.getUsername())) {
                throw new BadRequestException("缺少用户名");
            }
            if (StringUtils.isBlank(login.getPassword())) {
                throw new BadRequestException("缺少密码");
            }
            if (login.getUsername().equals("atv") || login.getUsername().equals("admin")) {
                throw new BadRequestException("用户名已被使用");
            }
            if ("guest".equals(login.getUsername())) {
                login.setUsername("dav");
            }
        }

        settingRepository.save(new Setting(ALIST_USERNAME, login.getUsername()));
        settingRepository.save(new Setting(ALIST_PASSWORD, login.getPassword()));
        settingRepository.save(new Setting(ALIST_LOGIN, String.valueOf(login.isEnabled())));

        String token = login();
        AListUser guest = getUser(2, token);
        if (guest != null) {
            guest.setPassword("alist_tvbox");
            guest.setDisabled(login.isEnabled());
            updateUser(guest, token);
        }

        deleteUser(3, token);
        if (login.isEnabled()) {
            AListUser user = new AListUser();
            user.setId(3);
            user.setUsername(login.getUsername());
            user.setPassword(login.getPassword());
            createUser(user, token);
        }
        return login;
    }

    public String login() {
        String username = "atv";
        String password = settingRepository.findById(ATV_PASSWORD).map(Setting::getValue).orElseThrow(BadRequestException::new);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        LoginResponse response = aListClient.postForObject("/api/auth/login", request, LoginResponse.class);
        log.debug("AList login response: {}", response);
        return response.getData().getToken();
    }

    private AListUser getUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<UserResponse> response = aListClient.exchange("/api/admin/user/get?id=" + id, HttpMethod.GET, entity, UserResponse.class);
        log.info("get AList user {} response: {}", id, response.getBody());
        return response.getBody().getData();
    }

    private void deleteUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = aListClient.exchange("/api/admin/user/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete AList user {} response: {}", id, response.getBody());
    }

    private void updateUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = aListClient.exchange("/api/admin/user/update", HttpMethod.POST, entity, String.class);
        log.info("update AList user {} response: {}", user.getId(), response.getBody());
    }

    private void createUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = aListClient.exchange("/api/admin/user/create", HttpMethod.POST, entity, String.class);
        log.info("create AList user response: {}", response.getBody());
    }

    public AListLogin getAListLoginInfo() {
        String username = settingRepository.findById(ALIST_USERNAME).map(Setting::getValue).orElse("");
        String password = settingRepository.findById(ALIST_PASSWORD).map(Setting::getValue).orElse("");
        String enabled = settingRepository.findById(ALIST_LOGIN).map(Setting::getValue).orElse("");
        AListLogin login = new AListLogin();
        login.setUsername(username);
        login.setPassword(password);
        login.setEnabled("true".equals(enabled));
        return login;
    }

    public List<CheckinLog> getCheckinLogs(Integer id) {
        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);
        Map<Object, Object> map = getAliToken(account.getRefreshToken());
        account.setNickname((String) map.get("nick_name"));
        String accessToken = (String) map.get(ACCESS_TOKEN);
        Map<String, Object> body = new HashMap<>();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, appProperties.getUserAgent());
        headers.set(HttpHeaders.REFERER, Constants.ALIPAN);
        headers.set("X-Canary", "client=web,app=adrive,version=v2.4.0");
        headers.set("X-Device-Id", "MpXKHKnbmzECAavdPTFxqhwD");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<CheckinResponse> response = restTemplate.exchange("https://member.aliyundrive.com/v2/activity/sign_in_list", HttpMethod.POST, entity, CheckinResponse.class);

        log.debug("sign_in_list: {}", response.getBody());
        List<CheckinLog> list = new ArrayList<>();
        CheckinResult result = response.getBody().getResult();
        if (result.getSignInCount() != account.getCheckinDays()) {
            account.setCheckinDays(result.getSignInCount());
            accountRepository.save(account);
        }
        LocalDate date = LocalDate.now();
        for (Map<String, Object> signInLog : result.getSignInInfos()) {
            date = date.withDayOfMonth(Integer.parseInt(signInLog.get("day").toString()));
            List<Map<String, Object>> rewards = (List<Map<String, Object>>) signInLog.get("rewards");
            CheckinLog log = new CheckinLog();
            log.setDate(date);
            log.setName(rewards.get(0).get("name").toString());
            log.setStatus(rewards.get(0).get("status").toString());
            if ("notStart".equals(log.getStatus())) {
                break;
            }
            list.add(log);

            log = new CheckinLog();
            log.setDate(date);
            log.setName(rewards.get(1).get("name").toString());
            log.setStatus(rewards.get(1).get("status").toString());
            list.add(log);
        }
        return list;
    }

    public CheckinResult checkin(Integer id, boolean force) {
        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);
        return checkin(account, force);
    }

    public CheckinResult checkin(Account account, boolean force) {
        if (StringUtils.isBlank(account.getRefreshToken())) {
            return null;
        }
        if (!force) {
            validateCheckinTime(account);
        }

        log.info("checkin for account {}:{}", account.getId(), account.getNickname());
        Map<Object, Object> map = getAliToken(account.getRefreshToken());
        String accessToken = (String) map.get(ACCESS_TOKEN);
        String refreshToken = (String) map.get(REFRESH_TOKEN);
        account.setNickname((String) map.get("nick_name"));
        account.setRefreshToken(refreshToken);
        account.setRefreshTokenTime(Instant.now());

        settingRepository.save(new Setting(REFRESH_TOKEN_TIME, Instant.now().toString()));

        Map<String, Object> body = new HashMap<>();
        body.put(REFRESH_TOKEN, refreshToken);
        body.put("grant_type", REFRESH_TOKEN);
        log.debug("body: {}", body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, appProperties.getUserAgent());
        headers.set(HttpHeaders.REFERER, Constants.ALIPAN);
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<CheckinResponse> response = restTemplate.exchange("https://member.aliyundrive.com/v1/activity/sign_in_list", HttpMethod.POST, entity, CheckinResponse.class);

        CheckinResult result = response.getBody().getResult();
        Instant now = Instant.now();
        result.setCheckinTime(now);
        account.setCheckinTime(Instant.now());

//        for (Map<String, Object> signInLog : result.getSignInLogs()) {
//            if (signInLog.get("status").equals("normal") && !signInLog.get("isReward").equals(true)) {
//                try {
//                    body = new HashMap<>();
//                    body.put("signInDay", signInLog.get("day"));
//                    log.debug("body: {}", body);
//
//                    headers = new HttpHeaders();
//                    headers.set(HttpHeaders.USER_AGENT, List.of(USER_AGENT));
//                    headers.set(HttpHeaders.REFERER, List.of("https://www.aliyundrive.com/"));
//                    headers.set(HttpHeaders.AUTHORIZATION, List.of("Bearer " + accessToken));
//                    entity = new HttpEntity<>(body, headers);
//                    ResponseEntity<RewardResponse> res = restTemplate.exchange("https://member.aliyundrive.com/v1/activity/sign_in_reward?_rx-s=mobile", HttpMethod.POST, entity, RewardResponse.class);
//                    log.debug("RewardResponse: {}", res.getBody());
//                    log.info("今日签到获得 {} {}", res.getBody().getResult().getName(), res.getBody().getResult().getDescription());
//                } catch (Exception e) {
//                    log.warn("领取奖励失败 {}", signInLog.get("day"), e);
//                }
//            }
//        }

        account.setCheckinDays(result.getSignInCount());
        log.info("{}  签到成功, 本月累计{}天", account.getNickname(), account.getCheckinDays());
        result.setSignInLogs(null);
        result.setNickname(account.getNickname());
        accountRepository.save(account);
        return result;
    }

    private void validateCheckinTime(Account account) {
        Instant checkinTime = account.getCheckinTime();
        if (checkinTime != null) {
            LocalDate time = checkinTime.atZone(ZoneId.of(ZONE_ID)).toLocalDate();
            if (LocalDate.now().isEqual(time)) {
                throw new BadRequestException(account.getNickname() + " 今日已签到");
            }
        }
    }

    public Instant updateScheduleTime(Instant time) {
        LocalTime localTime = time.atZone(ZoneId.of(ZONE_ID)).toLocalTime();
        settingRepository.save(new Setting(SCHEDULE_TIME, time.toString()));
        scheduledFuture.cancel(true);
        scheduledFuture = scheduler.schedule(this::handleScheduleTask, new CronTrigger(String.format("%d %d %d * * ?", localTime.getSecond(), localTime.getMinute(), localTime.getHour())));
        log.info("update schedule time: {}", localTime);
        return time;
    }

    public Account create(AccountDto dto) {
        long count = validateCreate(dto);
        Account account = new Account();
        account.setRefreshToken(dto.getRefreshToken().trim());
        account.setOpenToken(dto.getOpenToken().trim());
        account.setAutoCheckin(dto.isAutoCheckin());
        account.setShowMyAli(dto.isShowMyAli());
        account.setClean(dto.isClean());
        account.setUseProxy(dto.isUseProxy());
        account.setConcurrency(dto.getConcurrency());

        account.setMaster(dto.isMaster() || count == 0);
        if (account.isMaster()) {
            account.setShowMyAli(true);
        }
        accountRepository.save(account);

        if (count == 0) {
            updateTokens();
            int storageId = IDX + (account.getId() - 1) * 2;
            aListLocalService.setSetting("ali_account_id", String.valueOf(storageId), "number");
        } else if (account.isMaster()) {
            log.info("sync tokens for account {}", account);
            updateMaster(account);
            updateTokenToAList(account);
            updateAliAccountByApi(account);
        }

        checkin(account, false);
        showMyAliWithAPI(account);
        return account;
    }

    private long validateCreate(AccountDto dto) {
        long count = validate(dto);
        if (StringUtils.isNotBlank(dto.getRefreshToken()) && (accountRepository.existsByRefreshToken(dto.getRefreshToken()))) {
            throw new BadRequestException("阿里token重复");
        }
        return count;
    }

    private void updateTokenToAList(Account account) {
        try {
            String token = login();
            updateTokenToAList(account.getId(), "RefreshToken-" + account.getId(), account.getRefreshToken(), account.getRefreshTokenTime(), token);
            updateTokenToAList(account.getId(), "AccessToken-" + account.getId(), account.getAccessToken(), account.getAccessTokenTime(), token);
            updateTokenToAList(account.getId(), "RefreshTokenOpen-" + account.getId(), account.getOpenToken(), account.getOpenTokenTime(), token);
            updateTokenToAList(account.getId(), "AccessTokenOpen-" + account.getId(), account.getOpenAccessToken(), account.getOpenAccessTokenTime(), token);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private long validate(AccountDto dto) {
        long count = accountRepository.count();
        if (count == 0) {
            if (StringUtils.isBlank(dto.getRefreshToken())) {
                throw new BadRequestException("阿里token不能为空");
            }
            if (StringUtils.isBlank(dto.getOpenToken())) {
                throw new BadRequestException("开放token不能为空");
            }
        }

        if (StringUtils.isNotBlank(dto.getRefreshToken()) && dto.getRefreshToken().length() > 128) {
            throw new BadRequestException("阿里token长度太长");
        }
        if (StringUtils.isNotBlank(dto.getOpenToken()) && dto.getOpenToken().length() < 128) {
            throw new BadRequestException("开放token长度太短");
        }

        if (StringUtils.isAllBlank(dto.getRefreshToken(), dto.getOpenToken())) {
            throw new BadRequestException("至少需要一个token");
        }
        return count;
    }

    public void updateTokens(Integer id, AccountDto dto) {
        log.debug("update tokens: {} {}", id, dto);

        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);

        if (StringUtils.isNotBlank(dto.getRefreshToken())) {
            account.setRefreshToken(dto.getRefreshToken());
            account.setRefreshTokenTime(dto.getRefreshTokenTime());
        }

        if (StringUtils.isNotBlank(dto.getAccessToken())) {
            account.setAccessToken(dto.getAccessToken());
            account.setAccessTokenTime(dto.getAccessTokenTime());
        }

        if (StringUtils.isNotBlank(dto.getOpenToken())) {
            account.setOpenToken(dto.getOpenToken());
            account.setOpenTokenTime(dto.getOpenTokenTime());
        }

        if (StringUtils.isNotBlank(dto.getOpenAccessToken())) {
            account.setOpenAccessToken(dto.getOpenAccessToken());
            account.setOpenAccessTokenTime(dto.getOpenAccessTokenTime());
        }

        accountRepository.save(account);
    }

    public Account update(Integer id, AccountDto dto) {
        validateUpdate(id, dto);

        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);
        boolean tokenChanged = !Objects.equals(account.getRefreshToken(), dto.getRefreshToken()) || !Objects.equals(account.getOpenToken(), dto.getOpenToken());
        boolean changed = tokenChanged || account.isMaster() != dto.isMaster();
        boolean aliChanged = tokenChanged
                || account.isShowMyAli() != dto.isShowMyAli()
                || account.isUseProxy() != dto.isUseProxy()
                || !Objects.equals(account.getConcurrency(), dto.getConcurrency());

        account.setRefreshToken(dto.getRefreshToken().trim());
        account.setOpenToken(dto.getOpenToken().trim());
        account.setAutoCheckin(dto.isAutoCheckin());
        account.setShowMyAli(dto.isShowMyAli());
        account.setMaster(dto.isMaster());
        account.setClean(dto.isClean());
        account.setUseProxy(dto.isUseProxy());
        account.setConcurrency(dto.getConcurrency());

        if (changed && account.isMaster()) {
            updateMaster(account);
            updateAliAccountByApi(account);
        }

        if (aliChanged) {
            showMyAliWithAPI(account);
        }

        if (tokenChanged && account.isMaster()) {
            account.setOpenAccessToken("");
            log.info("sync tokens for account {}", account);
            updateTokenToAList(account);
        }

        return accountRepository.save(account);
    }

    private void updateAliAccountByApi(Account account) {
        int storageId = IDX + (account.getId() - 1) * 2;
        int status = aListLocalService.checkStatus();
        if (status == 1) {
            aListLocalService.executeUpdate("UPDATE x_setting_items SET value=" + storageId + " WHERE key = 'ali_account_id'");
            throw new BadRequestException("AList服务启动中");
        }

        String token = status >= 2 ? login() : "";
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        Map<String, Object> body = new HashMap<>();
        body.put("key", "ali_account_id");
        body.put("type", "number");
        body.put("flag", 1);
        body.put("value", String.valueOf(storageId));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = aListClient.exchange("/api/admin/setting/update", HttpMethod.POST, entity, String.class);
        log.info("updateAliAccountByApi {} response: {}", account.getId(), response.getBody());
    }

    private void updateMaster(Account account) {
        log.info("reset account master");
        List<Account> list = accountRepository.findAll();
        for (Account a : list) {
            a.setMaster(a.getId().equals(account.getId()));
            if (a.isMaster()) {
                a.setShowMyAli(true);
            }
        }
        accountRepository.saveAll(list);
    }

    private void validateUpdate(Integer id, AccountDto dto) {
        validate(dto);
        if (StringUtils.isNotBlank(dto.getRefreshToken())) {
            Account other = accountRepository.findByRefreshToken(dto.getRefreshToken());
            if (other != null && !id.equals(other.getId())) {
                throw new BadRequestException("阿里token重复");
            }
        }
    }

    public void showMyAli(Account account) {
        try {
            if (account.isShowMyAli() || account.isMaster()) {
                AliyundriveOpen storage = new AliyundriveOpen(account, "resource");
                aListLocalService.saveStorage(storage);
                storage = new AliyundriveOpen(account, "backup");
                aListLocalService.saveStorage(storage);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public void showMyAliWithAPI(Account account) {
        int status = aListLocalService.checkStatus();
        if (status == 1) {
            showMyAli(account);
            throw new BadRequestException("AList服务启动中");
        }

        String token = status >= 2 ? login() : "";
        int storageId = IDX + (account.getId() - 1) * 2;
        if (status == 0) {
            aListLocalService.executeUpdate("DELETE FROM x_storages WHERE id = " + storageId);
            aListLocalService.executeUpdate("DELETE FROM x_storages WHERE id = " + (storageId + 1));
        } else {
            deleteStorage(storageId, token);
            deleteStorage(storageId + 1, token);
        }

        try {
            String name = account.getNickname();
            if (StringUtils.isBlank(name)) {
                name = String.valueOf(account.getId());
            }
            if (account.isShowMyAli() || account.isMaster()) {
                AliyundriveOpen storage = new AliyundriveOpen(account, "resource");
                storage.setDisabled(true);
                aListLocalService.saveStorage(storage);
                storage = new AliyundriveOpen(account, "backup");
                storage.setDisabled(true);
                aListLocalService.saveStorage(storage);
                log.info("add AList storage {}", name);
                if (status >= 2) {
                    enableStorage(storageId, token);
                    enableStorage(storageId + 1, token);
                }
            } else {
                log.info("remove AList storage {}", name);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public void enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = aListClient.exchange("/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("enable AList storage {} response: {}", id, response.getBody());
    }

    public void deleteStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = aListClient.exchange("/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete AList storage {} response: {}", id, response.getBody());
    }

    public void delete(Integer id) {
        int status = aListLocalService.checkStatus();
        if (status == 1) {
            throw new BadRequestException("AList服务启动中");
        }

        Account account = accountRepository.findById(id).orElse(null);
        if (account != null) {
            accountRepository.deleteById(id);
            account.setShowMyAli(false);
            showMyAliWithAPI(account);
        }
    }

    private void updateTokenToAList(Integer accountId, String key, String value, Instant time, String token) {
        if (StringUtils.isEmpty(value)) {
            log.warn("Token is empty: {} {} ", accountId, key);
            return;
        }
        if (time == null) {
            time = Instant.now();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("value", value);
        body.put("accountId", accountId);
        body.put("modified", time.atOffset(ZONE_OFFSET).toString());
        log.debug("updateTokenToAList: {}", body);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = aListClient.exchange("/api/admin/token/update", HttpMethod.POST, entity, String.class);
        log.info("updateTokenToAList {} response: {}", key, response.getBody());
    }

    public String getAliRefreshToken(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return accountRepository.getFirstByMasterTrue()
                    .map(Account::getRefreshToken)
                    .orElseThrow(NotFoundException::new);
        }
        return null;
    }

    public String getAliOpenRefreshToken(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return accountRepository.getFirstByMasterTrue()
                    .map(Account::getOpenToken)
                    .orElseThrow(NotFoundException::new);
        }
        return null;
    }
}
