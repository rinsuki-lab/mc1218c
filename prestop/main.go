package main

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/gorcon/rcon"
)

const SECONDS=60

func main() {
	conn, err := rcon.Dial("127.0.0.1:25575", os.Getenv("RCON_PASSWORD"))
	if err != nil {
		panic(err)
	}
	conn.Execute("bossbar remove maintenance")
	conn.Execute(fmt.Sprintf("bossbar add maintenance \"サーバーメンテナンスまであと%d秒\"", SECONDS))
	conn.Execute("bossbar set maintenance color red")
	conn.Execute(fmt.Sprintf("bossbar set maintenance max %d", SECONDS))
	conn.Execute(fmt.Sprintf("bossbar set maintenance value %d", SECONDS))
	conn.Execute("bossbar set maintenance players @a")
	if SECONDS % 10 != 0 {
		conn.Execute(fmt.Sprintf("say サーバーメンテナンスのため、%d秒後にサーバーをシャットダウンします。できる限りシャットダウン前に安全な場所に移動して切断してください (全員が切断すると素早くシャットダウンできるため、早めの切断にご協力ください)", SECONDS))
	}
	for i := SECONDS; i >= 0; i-- {
		if (i % 10 == 0 && i > 0) || i == 5 {
			conn.Execute(fmt.Sprintf("say サーバーメンテナンスのため、%d秒後にサーバーをシャットダウンします。できる限りシャットダウン前に安全な場所に移動して切断してください (全員が切断すると素早くシャットダウンできるため、早めの切断にご協力ください)", i))
		}
		list, err := conn.Execute("list")
		if err == nil && strings.HasPrefix(list, "There are 0 of a max of") {
			conn.Execute("say \"オンラインプレーヤーがいなくなったため、早期終了します。\"")
			break
		}
		conn.Execute("bossbar set maintenance players @a")
		conn.Execute(fmt.Sprintf("bossbar set maintenance value %d", i))
		conn.Execute(fmt.Sprintf("bossbar set maintenance name \"サーバーメンテナンスまであと%d秒\"", i))
		time.Sleep(time.Second)
	}
	conn.Execute("bossbar remove maintenance")
	conn.Execute("save-all flush")
	conn.Execute("stop")
	for {
		time.Sleep(time.Second)
	}
}