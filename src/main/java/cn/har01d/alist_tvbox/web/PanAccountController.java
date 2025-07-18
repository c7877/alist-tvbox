package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.AccountInfo;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.service.DriverAccountService;
import cn.har01d.alist_tvbox.service.QuarkUCTV;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/pan/accounts")
public class PanAccountController {
    private final DriverAccountService driverAccountService;

    public PanAccountController(DriverAccountService driverAccountService) {
        this.driverAccountService = driverAccountService;
    }

    @GetMapping
    public List<DriverAccount> list() {
        return driverAccountService.list();
    }

    @PostMapping
    public DriverAccount create(@RequestBody DriverAccount account) {
        return driverAccountService.create(account);
    }

    @PostMapping("/{id}")
    public DriverAccount update(@PathVariable Integer id, @RequestBody DriverAccount account) {
        return driverAccountService.update(id, account);
    }

    @PostMapping("/{id}/token")
    public void updateToken(@PathVariable Integer id, @RequestBody DriverAccount account) {
        driverAccountService.updateToken(id, account);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        driverAccountService.delete(id);
    }

    @PostMapping("/-/qr")
    public QuarkUCTV.LoginResponse getQrCode(String type) throws IOException {
        return driverAccountService.getQrCode(type);
    }

    @PostMapping("/-/token")
    public AccountInfo getRefreshToken(String type, String queryToken) {
        return driverAccountService.getRefreshToken(type, queryToken);
    }

    @PostMapping("/-/info")
    public AccountInfo getInfo(@RequestBody DriverAccount account) {
        return driverAccountService.getInfo(account);
    }
}
