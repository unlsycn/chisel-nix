use clap::Parser;

pub mod dpi;
pub mod drive;
pub mod svdpi;

#[derive(Parser)]
pub(crate) struct GcdArgs {
    #[cfg(feature = "trace")]
    #[arg(long)]
    dump_start: u64,

    #[cfg(feature = "trace")]
    #[arg(long)]
    dump_end: u64,

    #[cfg(feature = "trace")]
    #[arg(long)]
    pub wave_path: String,

    #[arg(long, hide = true,default_value = env!("DESIGN_DATA_WIDTH"))]
    data_width: u64,

    #[arg(long, hide = true,default_value = env!("DESIGN_TIMEOUT"))]
    timeout: u64,

    #[arg(long, hide = true,default_value = env!("DESIGN_TEST_SIZE"))]
    test_size: u64,
}

#[cfg(feature = "sv2023")]
pub fn get_time() -> u64 {
    svdpi::get_time()
}
