import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { Controller, useForm } from "react-hook-form";
import { ThemeProvider, createTheme, alpha } from "@mui/material/styles";
import {
  Alert,
  Box,
  Button,
  Chip,
  Container,
  CssBaseline,
  FormControlLabel,
  Grid,
  Paper,
  Snackbar,
  Stack,
  Switch,
  TextField,
  Typography,
} from "@mui/material";

const monetTheme = createTheme({
  palette: {
    mode: "dark",
    primary: {
      main: "#d0bcff",
      light: "#eadcff",
      dark: "#9d8ac7",
      contrastText: "#381e72",
    },
    secondary: {
      main: "#ccc2dc",
      light: "#e8def8",
      dark: "#958da5",
      contrastText: "#332d41",
    },
    background: {
      default: "#111318",
      paper: "#1d1b20",
    },
    text: {
      primary: "#e7e0ec",
      secondary: "#cac4d0",
    },
    divider: "rgba(231, 224, 236, 0.12)",
    success: {
      main: "#b6f2c2",
      contrastText: "#003918",
    },
    error: {
      main: "#ffb4ab",
      contrastText: "#690005",
    },
  },
  shape: {
    borderRadius: 22,
  },
  typography: {
    fontFamily:
      'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    h4: {
      fontWeight: 760,
      letterSpacing: "-0.04em",
    },
    h6: {
      fontWeight: 700,
      letterSpacing: "-0.02em",
    },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          background:
            "radial-gradient(circle at 10% 0%, rgba(208, 188, 255, 0.24), transparent 28rem), radial-gradient(circle at 90% 10%, rgba(154, 209, 255, 0.18), transparent 24rem), radial-gradient(circle at 50% 100%, rgba(255, 180, 171, 0.14), transparent 28rem), #111318",
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: "none",
          border: "1px solid rgba(231, 224, 236, 0.10)",
          boxShadow: "0 24px 80px rgba(0, 0, 0, 0.36)",
        },
      },
    },
    MuiTextField: {
      defaultProps: {
        variant: "filled",
      },
      styleOverrides: {
        root: ({ theme }) => ({
          "& .MuiFilledInput-root": {
            borderRadius: 16,
            backgroundColor: alpha(theme.palette.primary.main, 0.07),
            border: "1px solid rgba(231, 224, 236, 0.10)",
            overflow: "hidden",
            "&:hover": {
              backgroundColor: alpha(theme.palette.primary.main, 0.1),
            },
            "&.Mui-focused": {
              backgroundColor: alpha(theme.palette.primary.main, 0.12),
              borderColor: alpha(theme.palette.primary.main, 0.58),
            },
            "&::before, &::after": {
              display: "none",
            },
          },
        }),
      },
    },
    MuiSwitch: {
      styleOverrides: {
        root: {
          padding: 8,
        },
        switchBase: {
          "&.Mui-checked + .MuiSwitch-track": {
            opacity: 1,
          },
        },
        track: {
          borderRadius: 999,
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 999,
          textTransform: "none",
          fontWeight: 700,
          boxShadow: "none",
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 999,
          fontWeight: 650,
        },
      },
    },
  },
});

type ServerConfig = {
  udp_ip: string;
  udp_port: number;
  tcp_ip: string;
  tcp_port: number;
  sensitivity: number;
  screen_width: number;
  zoom_level: number;
  angle_dead_zone: number;
  interpolation_sleep: number;
  accel_interpolation_steps: number;
  accel_zero_g: number;
  accel_filter_alpha: number;
  accel_target_hysteresis_steps: number;
  use_absolute_accel: boolean;
  accel_relative_sensitivity: number;
  max_mouse_delta: number;
  disable_accel_recenter: boolean;
  use_absolute_uinput: boolean;
  enable_hotkey_listener: boolean;
};

const integerKeys = [
  "udp_port",
  "tcp_port",
  "screen_width",
  "zoom_level",
  "accel_interpolation_steps",
  "accel_target_hysteresis_steps",
  "max_mouse_delta",
] as const;

const floatKeys = [
  "sensitivity",
  "angle_dead_zone",
  "interpolation_sleep",
  "accel_zero_g",
  "accel_filter_alpha",
  "accel_relative_sensitivity",
] as const;

function Section({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <Paper
      elevation={0}
      sx={{
        p: { xs: 2.25, sm: 3 },
        bgcolor: "rgba(33, 31, 38, 0.82)",
        backdropFilter: "blur(22px)",
      }}
    >
      <Stack spacing={0.75} sx={{ mb: 2.5 }}>
        <Typography variant="h6">{title}</Typography>
        {description ? (
          <Typography variant="body2" color="text.secondary">
            {description}
          </Typography>
        ) : null}
      </Stack>
      {children}
    </Paper>
  );
}

function ControlledSwitch({
  name,
  label,
  control,
}: {
  name: keyof ServerConfig;
  label: string;
  control: any;
}) {
  return (
    <Controller
      name={name as any}
      control={control}
      render={({ field }) => (
        <FormControlLabel
          sx={{
            width: "100%",
            m: 0,
            px: 1.5,
            py: 1,
            borderRadius: 4,
            bgcolor: "rgba(208, 188, 255, 0.06)",
            border: "1px solid rgba(231, 224, 236, 0.08)",
            justifyContent: "space-between",
          }}
          label={label}
          labelPlacement="start"
          control={
            <Switch
              checked={!!field.value}
              onChange={(_, checked) => field.onChange(checked)}
            />
          }
        />
      )}
    />
  );
}

export default function App() {
  const { register, handleSubmit, reset, control } = useForm<ServerConfig>();
  const [loading, setLoading] = useState(true);
  const [success, setSuccess] = useState(false);
  const [controlEnabled, setControlEnabled] = useState(true);

  useEffect(() => {
    Promise.all([invoke<ServerConfig>("get_config"), invoke<boolean>("get_control_state")])
      .then(([cfg, enabled]) => {
        reset(cfg);
        setControlEnabled(enabled);
      })
      .finally(() => setLoading(false));
  }, [reset]);

  useEffect(() => {
    const onKeyDown = async (event: KeyboardEvent) => {
      if (event.key !== "Backspace") return;

      const target = event.target as HTMLElement | null;
      const tagName = target?.tagName?.toLowerCase();
      const isEditing =
        tagName === "input" ||
        tagName === "textarea" ||
        target?.isContentEditable;

      if (isEditing) return;

      event.preventDefault();

      try {
        const enabled = await invoke<boolean>("toggle_control");
        setControlEnabled(enabled);
      } catch (error) {
        console.error(error);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  useEffect(() => {
    let unlisten: (() => void) | undefined;

    listen<boolean>("control-state-changed", (event) => {
      setControlEnabled(event.payload);
    })
      .then((fn) => {
        unlisten = fn;
      })
      .catch((error) => {
        console.error(error);
      });

    return () => {
      unlisten?.();
    };
  }, []);

  const onSubmit = async (data: ServerConfig) => {
    const parsed: ServerConfig = { ...data };

    integerKeys.forEach((key) => {
      parsed[key] = Number.parseInt(String(parsed[key]), 10) as never;
    });

    floatKeys.forEach((key) => {
      parsed[key] = Number.parseFloat(String(parsed[key])) as never;
    });

    try {
      const saved = await invoke<ServerConfig>("update_config", {
        newConfig: parsed,
      });
      reset(saved);
      setSuccess(true);
    } catch (error) {
      console.error(error);
    }
  };

  if (loading) {
    return (
      <ThemeProvider theme={monetTheme}>
        <CssBaseline />
        <Box sx={{ minHeight: "100vh", display: "grid", placeItems: "center" }}>
          <Typography color="text.secondary">正在加载配置…</Typography>
        </Box>
      </ThemeProvider>
    );
  }

  return (
    <ThemeProvider theme={monetTheme}>
      <CssBaseline />
      <Container maxWidth="lg" sx={{ py: { xs: 3, sm: 5 } }}>
        <Stack spacing={3.5}>
          <Paper
            elevation={0}
            sx={{
              p: { xs: 2.5, sm: 4 },
              overflow: "hidden",
              position: "relative",
              bgcolor: "rgba(29, 27, 32, 0.74)",
              backdropFilter: "blur(26px)",
            }}
          >
            <Box
              sx={{
                position: "absolute",
                inset: "auto -12% -90% auto",
                width: 360,
                height: 360,
                borderRadius: "50%",
                bgcolor: "primary.main",
                opacity: 0.12,
                filter: "blur(12px)",
              }}
            />
            <Stack
              direction={{ xs: "column", md: "row" }}
              spacing={2}
              sx={{
                position: "relative",
                alignItems: { xs: "flex-start", md: "center" },
                justifyContent: "space-between",
              }}
            >
              <Stack spacing={1}>
                <Typography variant="h4">Breakin Falsus Tauri</Typography>
              </Stack>
              <Stack
                direction="row"
                spacing={1}
                sx={{ flexWrap: "wrap", gap: 1 }}
              >
                <Chip
                  color={controlEnabled ? "success" : "default"}
                  label={`控制${controlEnabled ? "已启用" : "已暂停"}`}
                />
                <Chip variant="outlined" color="primary" label="Backspace 切换控制" />
              </Stack>
            </Stack>
          </Paper>

          <Box component="form" onSubmit={handleSubmit(onSubmit)}>
            <Grid container spacing={2.5}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Section title="网络" description="UDP / TCP 接收地址">
                  <Grid container spacing={2}>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField fullWidth label="UDP 地址" {...register("udp_ip")} />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField fullWidth label="UDP 端口" type="number" {...register("udp_port")} />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField fullWidth label="TCP 地址" {...register("tcp_ip")} />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField fullWidth label="TCP 端口" type="number" {...register("tcp_port")} />
                    </Grid>
                  </Grid>
                </Section>
              </Grid>

              <Grid size={{ xs: 12, md: 6 }}>
                <Section title="鼠标响应" description="陀螺仪灵敏度、屏幕宽度和单次移动限制。">
                  <Grid container spacing={2}>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField
                        fullWidth
                        label="灵敏度"
                        type="number"
                        slotProps={{ htmlInput: { step: "0.1" } }}
                        {...register("sensitivity")}
                      />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField fullWidth label="最大单次移动" type="number" {...register("max_mouse_delta")} />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField fullWidth label="屏幕宽度" type="number" {...register("screen_width")} />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField fullWidth label="缩放级别" type="number" {...register("zoom_level")} />
                    </Grid>
                  </Grid>
                </Section>
              </Grid>

              <Grid size={{ xs: 12, md: 7 }}>
                <Section title="加速度计" description="滤波、死区、插值和零点校准参数。">
                  <Grid container spacing={2}>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField
                        fullWidth
                        label="角度死区"
                        type="number"
                        slotProps={{ htmlInput: { step: "0.01" } }}
                        {...register("angle_dead_zone")}
                      />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField
                        fullWidth
                        label="相对移动灵敏度"
                        type="number"
                        slotProps={{ htmlInput: { step: "0.1" } }}
                        {...register("accel_relative_sensitivity")}
                      />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField
                        fullWidth
                        label="插值间隔"
                        type="number"
                        slotProps={{ htmlInput: { step: "0.001" } }}
                        {...register("interpolation_sleep")}
                      />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField
                        fullWidth
                        label="插值步数"
                        type="number"
                        {...register("accel_interpolation_steps")}
                      />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField
                        fullWidth
                        label="零重力基准"
                        type="number"
                        slotProps={{ htmlInput: { step: "0.00001" } }}
                        {...register("accel_zero_g")}
                      />
                    </Grid>
                    <Grid size={{ xs: 12, sm: 6 }}>
                      <TextField
                        fullWidth
                        label="滤波系数"
                        type="number"
                        slotProps={{ htmlInput: { step: "0.01" } }}
                        {...register("accel_filter_alpha")}
                      />
                    </Grid>
                    <Grid size={{ xs: 12 }}>
                      <TextField
                        fullWidth
                        label="目标迟滞步数"
                        type="number"
                        {...register("accel_target_hysteresis_steps")}
                      />
                    </Grid>
                  </Grid>
                </Section>
              </Grid>

              <Grid size={{ xs: 12, md: 5 }}>
                <Section title="Linux 模式" description="仅Linux生效配置，启用绝对 UInput 时会联动启用绝对加速度并允许回中。">
                  <Stack spacing={1.25}>
                    <ControlledSwitch control={control} name="use_absolute_accel" label="使用绝对加速度" />
                    <ControlledSwitch control={control} name="use_absolute_uinput" label="使用绝对 UInput" />
                    <ControlledSwitch control={control} name="disable_accel_recenter" label="禁用加速度回中" />
                    <ControlledSwitch control={control} name="enable_hotkey_listener" label="启用快捷键监听" />
                  </Stack>
                </Section>
              </Grid>

              <Grid size={{ xs: 12 }}>
                <Stack
                  direction="row"
                  spacing={1.5}
                  sx={{ justifyContent: "flex-end" }}
                >
                  <Button type="button" variant="outlined" color="secondary" onClick={() => invoke("get_config").then((cfg) => reset(cfg as ServerConfig))}>
                    重新加载
                  </Button>
                  <Button type="submit" variant="contained" color="primary" size="large">
                    保存配置
                  </Button>
                </Stack>
              </Grid>
            </Grid>
          </Box>
        </Stack>

        <Snackbar open={success} autoHideDuration={3000} onClose={() => setSuccess(false)}>
          <Alert severity="success" onClose={() => setSuccess(false)}>
            配置已保存。
          </Alert>
        </Snackbar>
      </Container>
    </ThemeProvider>
  );
}
